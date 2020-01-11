package github.scarsz.shareserver;

import org.apache.commons.lang3.StringUtils;

import java.util.HashSet;
import java.util.Set;

public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            new ShareServer();
        } else {
            Set<String> keys = new HashSet<>();
            int port = 6478;

            for (String arg : args) {
                if (StringUtils.isNumeric(arg)) {
                    int targetPort = Integer.parseInt(arg);
                    if (targetPort > 0 && targetPort < 65535) {
                        port = targetPort;
                    }
                } else {
                    keys.add(arg);
                }
            }

            new ShareServer(keys, port);
        }
    }

}
