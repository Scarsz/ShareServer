package github.scarsz.shareserver;

public class Main {

    public static void main(String[] args) throws Exception {
        new ShareServer(
                args.length >= 1 ? args[0] : null,
                args.length >= 2 ? Integer.parseInt(args[1]) : 8082
        );
    }

}
