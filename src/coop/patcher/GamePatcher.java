package coop.patcher;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Applies the co-op hooks to the game's classes.
 *
 * All classes in package "Game" are widened (everything public) so the mod can read the
 * game state, and small call-outs to coop.Hooks are inserted at the places listed in Patches.
 */
public final class GamePatcher implements Opcodes {

    /** One code change inside one game method. */
    public static final class Patch {
        final String owner;
        final String method;
        final String desc; // null = every overload
        final Kind kind;
        final String hookName;
        final String hookDesc;
        final boolean passThis;
        final boolean passArgs;
        final String target; // owner.name(desc) for call based patches
        final boolean optional;
        int applied;

        Patch(String owner, String method, String desc, Kind kind, String hookName, String hookDesc,
                boolean passThis, boolean passArgs, String target, boolean optional) {
            this.owner = owner;
            this.method = method;
            this.desc = desc;
            this.kind = kind;
            this.hookName = hookName;
            this.hookDesc = hookDesc;
            this.passThis = passThis;
            this.passArgs = passArgs;
            this.target = target;
            this.optional = optional;
        }

        @Override
        public String toString() {
            return kind + " " + owner + "." + method + (desc == null ? "" : desc) + (target == null ? "" : " @" + target)
                    + " -> Hooks." + hookName;
        }
    }

    public enum Kind {
        /** call the hook at method start */
        HEAD,
        /** call the hook at method start, return immediately (default value) when it returns true */
        HEAD_CANCEL,
        /** call the hook before every return */
        TAIL,
        /** replace a method call (target) by a static call to the hook with the same arguments (+receiver) */
        REDIRECT,
        /** call the hook right after a call to target */
        AFTER_CALL,
        /** call the hook right before a call to target */
        BEFORE_CALL,
        /** replace "new X(...)" constructions (target = X) by nothing when the hook says so: skip constructor call */
        SKIP_NEW,
        /** call the hook right after a PUTFIELD/PUTSTATIC of field target (owner.name) */
        FIELD_AFTER,
        /** replace "SIPUSH 300" directly followed by GETSTATIC *.addplayer with a call to the hook (returns int) */
        LIMIT_ADDPLAYER,
        /** replace every "SIPUSH 300" in the method with a call to the hook (returns int) */
        REPLACE_300,
        /** after every LDC of a String containing target, call hook(String)String; owner/method may be "*" */
        LDC_STRING,
        /**
         * in loops "for (x...; x < 300 + addplayer; x++)" (also via a local holding 300 + addplayer):
         * skip the body when hook(x) returns true
         */
        LOOP_SKIP_SLOT,
        /** replace every GETSTATIC of a field (target = field name + ":" + descriptor, any owner) by hook()desc */
        FIELD_GET,
        /**
         * before a TABLESWITCH over local S: hook(S, a1, a2...) returns the new switch value
         * (target = "S:a1,a2", all int locals)
         */
        SWITCH_ARG
    }

    public static final String HOOKS = "coop/Hooks";

    private final List<Patch> patches = new ArrayList<>();
    private final Map<String, String> superOf = new HashMap<>();
    /** return instructions added by HEAD_CANCEL: TAIL hooks must not run there */
    private final java.util.Set<AbstractInsnNode> injectedReturns =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final StringBuilder log = new StringBuilder();

    public GamePatcher add(Patch p) {
        patches.add(p);
        return this;
    }

    public List<Patch> patches() {
        return patches;
    }

    public String log() {
        return log.toString();
    }

    /** Reads the game jar, applies widening + hooks and returns the new jar. */
    public byte[] patchJar(byte[] jar, boolean applyHooks) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(jar))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                entries.put(e.getName(), zin.readAllBytes());
            }
        }
        // class hierarchy for frame computation
        for (Map.Entry<String, byte[]> en : entries.entrySet()) {
            if (en.getKey().endsWith(".class") && !en.getKey().equals("module-info.class")) {
                ClassReader cr = new ClassReader(en.getValue());
                superOf.put(cr.getClassName(), cr.getSuperName());
            }
        }
        ByteArrayOutputStream bout = new ByteArrayOutputStream(jar.length + 65536);
        try (ZipOutputStream zout = new ZipOutputStream(bout)) {
            for (Map.Entry<String, byte[]> en : entries.entrySet()) {
                String name = en.getKey();
                byte[] data = en.getValue();
                if (name.equals("module-info.class")) {
                    continue; // run as plain classpath application
                }
                if (name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA"))) {
                    continue;
                }
                if (name.startsWith("Game/") && name.endsWith(".class")) {
                    data = transform(data, applyHooks);
                }
                zout.putNextEntry(new ZipEntry(name));
                zout.write(data);
                zout.closeEntry();
            }
        }
        if (applyHooks) {
            StringBuilder missing = new StringBuilder();
            for (Patch p : patches) {
                if (p.applied == 0 && !p.optional) {
                    missing.append("  ").append(p).append('\n');
                }
                log.append(p.applied > 0 ? "OK   " : (p.optional ? "skip " : "FAIL ")).append(p).append(" x").append(p.applied).append('\n');
            }
            if (missing.length() > 0) {
                throw new IOException("Diese Spielversion passt nicht zum Coop-Mod. Fehlende Stellen:\n" + missing);
            }
        }
        return bout.toByteArray();
    }

    private byte[] transform(byte[] data, boolean applyHooks) {
        ClassNode cn = new ClassNode();
        new ClassReader(data).accept(cn, 0);
        widen(cn);
        boolean changed = false;
        if (applyHooks) {
            for (MethodNode mn : cn.methods) {
                for (Patch p : patches) {
                    boolean ownerOk = p.owner.equals(cn.name) || p.owner.equals("*");
                    boolean methodOk = p.method.equals(mn.name) || p.method.equals("*");
                    if (!ownerOk || !methodOk) {
                        continue;
                    }
                    if (p.desc != null && !p.desc.equals(mn.desc)) {
                        continue;
                    }
                    int n = apply(cn, mn, p);
                    p.applied += n;
                    changed |= n > 0;
                }
            }
        }
        ClassWriter cw = new ClassWriter(changed ? ClassWriter.COMPUTE_FRAMES : 0) {
            @Override
            protected String getCommonSuperClass(String a, String b) {
                return commonSuper(a, b);
            }
        };
        cn.accept(cw);
        return cw.toByteArray();
    }

    private static void widen(ClassNode cn) {
        cn.access = (cn.access & ~(ACC_PRIVATE | ACC_PROTECTED)) | ACC_PUBLIC;
        for (FieldNode f : cn.fields) {
            f.access = (f.access & ~(ACC_PRIVATE | ACC_PROTECTED)) | ACC_PUBLIC;
            if ((f.access & ACC_STATIC) == 0 || f.value == null) {
                f.access &= ~ACC_FINAL;
            }
        }
        for (MethodNode m : cn.methods) {
            if (m.name.equals("<clinit>")) {
                continue;
            }
            m.access = (m.access & ~(ACC_PRIVATE | ACC_PROTECTED)) | ACC_PUBLIC;
        }
        for (InnerClassNode ic : cn.innerClasses) {
            if (ic.name.startsWith("Game/")) {
                ic.access = (ic.access & ~(ACC_PRIVATE | ACC_PROTECTED)) | ACC_PUBLIC;
            }
        }
    }

    private int apply(ClassNode cn, MethodNode mn, Patch p) {
        boolean isStatic = (mn.access & ACC_STATIC) != 0;
        Type ret = Type.getReturnType(mn.desc);
        switch (p.kind) {
            case HEAD: {
                InsnList il = loadArgs(cn, mn, p, isStatic);
                il.add(new MethodInsnNode(INVOKESTATIC, HOOKS, p.hookName, p.hookDesc, false));
                insertHead(mn, il);
                return 1;
            }
            case HEAD_CANCEL: {
                InsnList il = loadArgs(cn, mn, p, isStatic);
                il.add(new MethodInsnNode(INVOKESTATIC, HOOKS, p.hookName, p.hookDesc, false));
                LabelNode cont = new LabelNode();
                il.add(new JumpInsnNode(IFEQ, cont));
                InsnList r = defaultReturn(ret);
                injectedReturns.add(r.getLast());
                il.add(r);
                il.add(cont);
                insertHead(mn, il);
                return 1;
            }
            case TAIL: {
                int n = 0;
                for (AbstractInsnNode in = mn.instructions.getFirst(); in != null; in = in.getNext()) {
                    int op = in.getOpcode();
                    if (op >= IRETURN && op <= RETURN && !injectedReturns.contains(in)) {
                        InsnList il = loadArgs(cn, mn, p, isStatic);
                        il.add(new MethodInsnNode(INVOKESTATIC, HOOKS, p.hookName, p.hookDesc, false));
                        mn.instructions.insertBefore(in, il);
                        n++;
                    }
                }
                return n;
            }
            case REDIRECT:
            case AFTER_CALL:
            case BEFORE_CALL: {
                int n = 0;
                for (AbstractInsnNode in = mn.instructions.getFirst(); in != null; in = in.getNext()) {
                    if (!(in instanceof MethodInsnNode)) {
                        continue;
                    }
                    MethodInsnNode mi = (MethodInsnNode) in;
                    if (!(mi.owner + "." + mi.name + mi.desc).equals(p.target)) {
                        continue;
                    }
                    if (p.kind == Kind.REDIRECT) {
                        MethodInsnNode repl = new MethodInsnNode(INVOKESTATIC, HOOKS, p.hookName, p.hookDesc, false);
                        mn.instructions.set(mi, repl);
                        in = repl;
                    } else {
                        InsnList il = loadArgs(cn, mn, p, isStatic);
                        il.add(new MethodInsnNode(INVOKESTATIC, HOOKS, p.hookName, p.hookDesc, false));
                        if (p.kind == Kind.AFTER_CALL) {
                            AbstractInsnNode last = il.getLast();
                            mn.instructions.insert(mi, il);
                            in = last;
                        } else {
                            mn.instructions.insertBefore(mi, il);
                        }
                    }
                    n++;
                }
                return n;
            }
            case SKIP_NEW: {
                // target = internal class name; replaces "NEW X; DUP; <args>; INVOKESPECIAL X.<init>" result usage
                // We only support "new X(...)" used as a statement: NEW, DUP, args..., INVOKESPECIAL, POP
                int n = 0;
                for (AbstractInsnNode in = mn.instructions.getFirst(); in != null; in = in.getNext()) {
                    if (in.getOpcode() == NEW && ((TypeInsnNode) in).desc.equals(p.target)) {
                        AbstractInsnNode dup = in.getNext();
                        AbstractInsnNode init = dup;
                        while (init != null && !(init.getOpcode() == INVOKESPECIAL
                                && ((MethodInsnNode) init).owner.equals(p.target)
                                && ((MethodInsnNode) init).name.equals("<init>"))) {
                            init = init.getNext();
                        }
                        if (init == null || dup.getOpcode() != DUP) {
                            continue;
                        }
                        String initDesc = ((MethodInsnNode) init).desc;
                        if (Type.getArgumentTypes(initDesc).length != 0 || init.getNext() == null
                                || init.getNext().getOpcode() != POP) {
                            continue;
                        }
                        // if (Hooks.hook()) skip the construction
                        LabelNode skip = new LabelNode();
                        LabelNode end = new LabelNode();
                        InsnList pre = new InsnList();
                        pre.add(new MethodInsnNode(INVOKESTATIC, HOOKS, p.hookName, p.hookDesc, false));
                        pre.add(new JumpInsnNode(IFNE, skip));
                        mn.instructions.insertBefore(in, pre);
                        AbstractInsnNode pop = init.getNext();
                        InsnList post = new InsnList();
                        post.add(new JumpInsnNode(GOTO, end));
                        post.add(skip);
                        post.add(end);
                        mn.instructions.insert(pop, post);
                        in = pop;
                        n++;
                    }
                }
                return n;
            }
            case FIELD_AFTER: {
                int n = 0;
                for (AbstractInsnNode in = mn.instructions.getFirst(); in != null; in = in.getNext()) {
                    if ((in.getOpcode() == PUTFIELD || in.getOpcode() == PUTSTATIC)
                            && (((FieldInsnNode) in).owner + "." + ((FieldInsnNode) in).name).equals(p.target)) {
                        InsnList il = loadArgs(cn, mn, p, isStatic);
                        il.add(new MethodInsnNode(INVOKESTATIC, HOOKS, p.hookName, p.hookDesc, false));
                        AbstractInsnNode last = il.getLast();
                        mn.instructions.insert(in, il);
                        in = last;
                        n++;
                    }
                }
                return n;
            }
            case LIMIT_ADDPLAYER:
            case REPLACE_300: {
                int n = 0;
                for (AbstractInsnNode in = mn.instructions.getFirst(); in != null; in = in.getNext()) {
                    if (in.getOpcode() != SIPUSH || ((IntInsnNode) in).operand != 300) {
                        continue;
                    }
                    if (p.kind == Kind.LIMIT_ADDPLAYER) {
                        AbstractInsnNode nx = in.getNext();
                        while (nx != null && nx.getOpcode() == -1) {
                            nx = nx.getNext();
                        }
                        if (nx == null || nx.getOpcode() != GETSTATIC || !((FieldInsnNode) nx).name.equals("addplayer")) {
                            continue;
                        }
                    }
                    MethodInsnNode repl = new MethodInsnNode(INVOKESTATIC, HOOKS, p.hookName, p.hookDesc, false);
                    mn.instructions.set(in, repl);
                    in = repl;
                    n++;
                }
                return n;
            }
            case LOOP_SKIP_SLOT:
                return loopSkip(mn, p);
            case SWITCH_ARG: {
                int n = 0;
                int sv = Integer.parseInt(p.target.substring(0, p.target.indexOf(':')));
                String[] args = p.target.substring(p.target.indexOf(':') + 1).split(",");
                for (AbstractInsnNode in = mn.instructions.getFirst(); in != null; in = in.getNext()) {
                    if (in.getOpcode() != TABLESWITCH) {
                        continue;
                    }
                    AbstractInsnNode ld = realPrev(in);
                    if (ld == null || ld.getOpcode() != ILOAD || ((VarInsnNode) ld).var != sv) {
                        continue;
                    }
                    InsnList il = new InsnList();
                    for (String a : args) {
                        il.add(new VarInsnNode(ILOAD, Integer.parseInt(a.trim())));
                    }
                    il.add(new MethodInsnNode(INVOKESTATIC, HOOKS, p.hookName, p.hookDesc, false));
                    mn.instructions.insert(ld, il);
                    n++;
                }
                return n;
            }
            case FIELD_GET: {
                int n = 0;
                String name = p.target.substring(0, p.target.indexOf(':'));
                String fdesc = p.target.substring(p.target.indexOf(':') + 1);
                for (AbstractInsnNode in = mn.instructions.getFirst(); in != null; in = in.getNext()) {
                    if (in.getOpcode() == GETSTATIC && ((FieldInsnNode) in).name.equals(name)
                            && ((FieldInsnNode) in).desc.equals(fdesc)) {
                        MethodInsnNode call = new MethodInsnNode(INVOKESTATIC, HOOKS, p.hookName, p.hookDesc, false);
                        mn.instructions.set(in, call);
                        in = call;
                        n++;
                    }
                }
                return n;
            }
            case LDC_STRING: {
                int n = 0;
                for (AbstractInsnNode in = mn.instructions.getFirst(); in != null; in = in.getNext()) {
                    if (in.getOpcode() == LDC && ((LdcInsnNode) in).cst instanceof String
                            && ((String) ((LdcInsnNode) in).cst).contains(p.target)) {
                        MethodInsnNode call = new MethodInsnNode(INVOKESTATIC, HOOKS, p.hookName, p.hookDesc, false);
                        mn.instructions.insert(in, call);
                        in = call;
                        n++;
                    }
                }
                return n;
            }
            default:
                return 0;
        }
    }

    private static AbstractInsnNode realNext(AbstractInsnNode in) {
        AbstractInsnNode n = in.getNext();
        while (n != null && n.getOpcode() == -1) {
            n = n.getNext();
        }
        return n;
    }

    private static AbstractInsnNode realPrev(AbstractInsnNode in) {
        AbstractInsnNode n = in.getPrevious();
        while (n != null && n.getOpcode() == -1) {
            n = n.getPrevious();
        }
        return n;
    }

    private static boolean isAddplayer(AbstractInsnNode in) {
        return in != null && in.getOpcode() == GETSTATIC && ((FieldInsnNode) in).name.equals("addplayer");
    }

    /**
     * Loops compiled as "IINC v,1; [label] ILOAD v; (SIPUSH 300; GETSTATIC addplayer; IADD | ILOAD bound); IF_ICMPLT body"
     * get "ILOAD v; INVOKESTATIC hook(I)Z; IFNE skip" at the start of the body, skip = new label before the IINC.
     */
    private static int loopSkip(MethodNode mn, Patch p) {
        java.util.Set<Integer> bounds = new java.util.HashSet<>();
        for (AbstractInsnNode in = mn.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() == SIPUSH && ((IntInsnNode) in).operand == 300) {
                AbstractInsnNode g = realNext(in);
                AbstractInsnNode add = g == null ? null : realNext(g);
                AbstractInsnNode st = add == null ? null : realNext(add);
                if (isAddplayer(g) && add.getOpcode() == IADD && st != null && st.getOpcode() == ISTORE) {
                    bounds.add(((VarInsnNode) st).var);
                }
            }
        }
        List<AbstractInsnNode[]> sites = new ArrayList<>();
        for (AbstractInsnNode in = mn.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() != IF_ICMPLT) {
                continue;
            }
            AbstractInsnNode ld;
            AbstractInsnNode b = realPrev(in);
            int onlyVar = -1;
            if (p.target != null) {
                // "BOUND" or "BOUND:VAR" (only the loop over local VAR)
                String[] t = p.target.split(":");
                int bound = Integer.parseInt(t[0]);
                onlyVar = t.length > 1 ? Integer.parseInt(t[1]) : -1;
                if (b == null || b.getOpcode() != SIPUSH || ((IntInsnNode) b).operand != bound) {
                    continue;
                }
                ld = realPrev(b);
            } else if (b != null && b.getOpcode() == IADD) {
                AbstractInsnNode g = realPrev(b);
                AbstractInsnNode s300 = g == null ? null : realPrev(g);
                if (!isAddplayer(g) || s300 == null || s300.getOpcode() != SIPUSH || ((IntInsnNode) s300).operand != 300) {
                    continue;
                }
                ld = realPrev(s300);
            } else if (b != null && b.getOpcode() == ILOAD && bounds.contains(((VarInsnNode) b).var)) {
                ld = realPrev(b);
            } else {
                continue;
            }
            if (ld == null || ld.getOpcode() != ILOAD) {
                continue;
            }
            int var = ((VarInsnNode) ld).var;
            if (onlyVar >= 0 && var != onlyVar) {
                continue;
            }
            AbstractInsnNode inc = realPrev(ld);
            if (!(inc instanceof IincInsnNode) || ((IincInsnNode) inc).var != var || ((IincInsnNode) inc).incr != 1) {
                continue;
            }
            sites.add(new AbstractInsnNode[] {in, inc});
        }
        for (AbstractInsnNode[] site : sites) {
            JumpInsnNode jump = (JumpInsnNode) site[0];
            IincInsnNode inc = (IincInsnNode) site[1];
            LabelNode skip = new LabelNode();
            mn.instructions.insertBefore(inc, skip);
            InsnList il = new InsnList();
            il.add(new VarInsnNode(ILOAD, inc.var));
            il.add(new MethodInsnNode(INVOKESTATIC, HOOKS, p.hookName, p.hookDesc, false));
            il.add(new JumpInsnNode(IFNE, skip));
            mn.instructions.insert(jump.label, il);
        }
        return sites.size();
    }

    private static void insertHead(MethodNode mn, InsnList il) {
        if (mn.name.equals("<init>")) {
            // after the super constructor call
            for (AbstractInsnNode in = mn.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in.getOpcode() == INVOKESPECIAL && ((MethodInsnNode) in).name.equals("<init>")) {
                    mn.instructions.insert(in, il);
                    return;
                }
            }
        }
        mn.instructions.insert(il);
    }

    private static InsnList loadArgs(ClassNode cn, MethodNode mn, Patch p, boolean isStatic) {
        InsnList il = new InsnList();
        if (p.passThis && !isStatic) {
            il.add(new VarInsnNode(ALOAD, 0));
        }
        if (p.passArgs) {
            int slot = isStatic ? 0 : 1;
            for (Type t : Type.getArgumentTypes(mn.desc)) {
                il.add(new VarInsnNode(t.getOpcode(ILOAD), slot));
                slot += t.getSize();
            }
        }
        return il;
    }

    private static InsnList defaultReturn(Type ret) {
        InsnList il = new InsnList();
        switch (ret.getSort()) {
            case Type.VOID:
                il.add(new InsnNode(RETURN));
                break;
            case Type.BOOLEAN:
            case Type.BYTE:
            case Type.CHAR:
            case Type.SHORT:
            case Type.INT:
                il.add(new InsnNode(ICONST_0));
                il.add(new InsnNode(IRETURN));
                break;
            case Type.LONG:
                il.add(new InsnNode(LCONST_0));
                il.add(new InsnNode(LRETURN));
                break;
            case Type.FLOAT:
                il.add(new InsnNode(FCONST_0));
                il.add(new InsnNode(FRETURN));
                break;
            case Type.DOUBLE:
                il.add(new InsnNode(DCONST_0));
                il.add(new InsnNode(DRETURN));
                break;
            default:
                il.add(new InsnNode(ACONST_NULL));
                il.add(new InsnNode(ARETURN));
        }
        return il;
    }

    private String superName(String type) {
        if (superOf.containsKey(type)) {
            return superOf.get(type);
        }
        try {
            Class<?> c = Class.forName(type.replace('/', '.'), false, GamePatcher.class.getClassLoader());
            if (c.isInterface()) {
                return null;
            }
            Class<?> s = c.getSuperclass();
            String r = s == null ? null : s.getName().replace('.', '/');
            superOf.put(type, r);
            return r;
        } catch (Throwable t) {
            superOf.put(type, "java/lang/Object");
            return "java/lang/Object";
        }
    }

    private String commonSuper(String a, String b) {
        if (a.equals(b)) {
            return a;
        }
        List<String> chainA = new ArrayList<>();
        for (String t = a; t != null; t = superName(t)) {
            chainA.add(t);
        }
        for (String t = b; t != null; t = superName(t)) {
            if (chainA.contains(t)) {
                return t;
            }
        }
        return "java/lang/Object";
    }
}
