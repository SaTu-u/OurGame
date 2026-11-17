package dev.claudebridge;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Allowed client addresses: exact IPs or CIDR ranges ("203.0.113.10", "203.0.113.0/24", "2001:db8::/32").
 * Host names are resolved once when the list is built. An empty list allows everyone.
 * Only the direct TCP peer address is checked; X-Forwarded-For is ignored on purpose (it can be forged).
 */
final class IpAllowList {

    private record Rule(byte[] network, int prefix, String source) {
    }

    private final List<Rule> rules = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();

    IpAllowList(List<String> entries) {
        for (String raw : entries) {
            if (raw == null || raw.isBlank()) continue;
            String e = raw.trim();
            String host = e;
            int prefix = -1;
            int slash = e.indexOf('/');
            if (slash >= 0) {
                host = e.substring(0, slash);
                try {
                    prefix = Integer.parseInt(e.substring(slash + 1));
                } catch (NumberFormatException ex) {
                    problems.add("bad prefix in '" + e + "'");
                    continue;
                }
            }
            try {
                for (InetAddress a : InetAddress.getAllByName(host)) {
                    byte[] bytes = a.getAddress();
                    int p = prefix < 0 ? bytes.length * 8 : Math.min(prefix, bytes.length * 8);
                    rules.add(new Rule(bytes, p, e));
                }
            } catch (UnknownHostException ex) {
                problems.add("cannot resolve '" + e + "'");
            }
        }
    }

    boolean isEmpty() {
        return rules.isEmpty() && problems.isEmpty();
    }

    List<String> problems() {
        return problems;
    }

    boolean allows(InetAddress address) {
        if (rules.isEmpty()) return problems.isEmpty(); // misconfigured list: fail closed
        if (address == null) return false;
        byte[] a = address.getAddress();
        for (Rule r : rules) {
            if (r.network().length != a.length) continue;
            if (matches(a, r.network(), r.prefix())) return true;
        }
        return false;
    }

    private static boolean matches(byte[] addr, byte[] net, int prefix) {
        int full = prefix / 8;
        for (int i = 0; i < full; i++) if (addr[i] != net[i]) return false;
        int rest = prefix % 8;
        if (rest == 0) return true;
        int mask = (0xFF << (8 - rest)) & 0xFF;
        return (addr[full] & mask) == (net[full] & mask);
    }

    @Override
    public String toString() {
        List<String> s = new ArrayList<>();
        for (Rule r : rules) s.add(r.source());
        return s.toString();
    }
}
