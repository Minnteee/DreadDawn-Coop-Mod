package coop.patcher;

import java.nio.file.Files;
import java.nio.file.Path;

/** Build-time helper: "widen EXE OUT.jar" (for compiling the mod) or "patch EXE OUT.jar" (full patch). */
public final class Tool {
    private Tool() {}

    public static void main(String[] args) throws Exception {
        String cmd = args[0];
        byte[] jar = Exe4j.extractGameJar(Path.of(args[1]));
        GamePatcher gp = Patches.create();
        byte[] out = gp.patchJar(jar, cmd.equals("patch"));
        Files.write(Path.of(args[2]), out);
        if (cmd.equals("patch")) {
            System.out.print(gp.log());
        }
        System.out.println(cmd + " ok: " + args[2] + " (" + out.length + " bytes)");
    }
}
