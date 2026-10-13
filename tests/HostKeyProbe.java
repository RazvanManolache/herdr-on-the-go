import io.github.razvanmanolache.herdronthego.KnownHostsStore;
import io.github.razvanmanolache.herdronthego.SshBridge;

/** Runs production SSH bytecode with loopback-only synthetic credentials. */
public final class HostKeyProbe {
    public static void main(String[] args) throws Exception {
        String mode = args[1];
        int[] prompts = {0}, writes = {0};
        KnownHostsStore store = new KnownHostsStore() {
            public String get(int port, String host) {
                if (mode.equals("known")) return args[2];
                return mode.equals("changed") ? "different-key" : null;
            }
            public void put(int port, String host, String key) {
                if (!mode.equals("accepted") || !key.equals(args[2])) throw new AssertionError("Unexpected pin");
                writes[0]++;
            }
        };
        SshBridge bridge = new SshBridge(store);
        try {
            bridge.connect("127.0.0.1", Integer.parseInt(args[0]),
                "test-only-user", "synthetic-test-password", 10000, fp -> {
                    prompts[0]++;
                    System.out.println("PROMPT " + fp);
                    return mode.equals("accepted");
                });
            if (!mode.equals("accepted") && !mode.equals("known")) throw new AssertionError("Connected to rejected host");
            System.out.println("CONNECTED");
        } catch (SecurityException expected) {
            if (mode.equals("accepted") || mode.equals("known")) throw expected;
            System.out.println("REJECTED");
        } finally { bridge.disconnect(); }
        int expectedPrompts = mode.equals("accepted") || mode.equals("unknown-rejected") ? 1 : 0;
        if (prompts[0] != expectedPrompts || writes[0] != (mode.equals("accepted") ? 1 : 0)) throw new AssertionError("Trust flow");
    }
}
