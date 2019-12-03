package github.scarsz.shareserver;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;

import javax.servlet.MultipartConfigElement;
import java.io.File;
import java.io.InputStream;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

import static spark.Spark.*;

public class ShareServer {

    private final Thread shutdownHook;
    private Connection connection;
    private LineReader console;

    public ShareServer(String key, int port) throws Exception {
        if (key == null) throw new IllegalArgumentException("No key given");

        initDatabase();
        initSpark(port, key);
        Runtime.getRuntime().addShutdownHook(shutdownHook = new Thread(this::shutdown));
        readConsole();
    }

    private void shutdown() {
        log("Closing database connection");
        try {
            connection.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }

        log("Goodbye");
        System.exit(0);
    }

    private void initDatabase() throws SQLException {
        if (connection != null) connection.close();

        Map<String, Object> arguments = new HashMap<>();
        arguments.put("TRACE_LEVEL_FILE", 0);
        arguments.put("TRACE_LEVEL_SYSTEM_OUT", 1);
        String argumentsString = ";" + arguments.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.joining(";"));
        connection = DriverManager.getConnection("jdbc:h2:" + new File("share").getAbsolutePath() + argumentsString);
        connection.prepareStatement("CREATE TABLE IF NOT EXISTS `files` (" +
                "`id` VARCHAR NOT NULL, " +
                "`filename` VARCHAR NOT NULL, " +
                "`hits` INT NOT NULL DEFAULT 0, " +
                "`type` VARCHAR NOT NULL, " +
                "`data` BLOB NOT NULL, " +
                "PRIMARY KEY (`id`), UNIQUE KEY id (`id`))").executeUpdate();
    }

    private void initSpark(int port, String key) {
        port(port);

        // gzip where possible
        after((request, response) -> response.header("Content-Encoding", "gzip"));

        // logging
        afterAfter((request, response) -> {
            String forwardedFor = request.headers("X-Forwarded-For");
            String ip = StringUtils.isNotBlank(forwardedFor) ? forwardedFor : request.ip();
            String method = request.requestMethod();
            String location = request.url() + (StringUtils.isNotBlank(request.queryString()) ? "?" + request.queryString() : "");
            log(ip + ":" + request.raw().getRemotePort() + " " + method + " " + location + " -> " + response.status());
        });

        // redirect /id -> /id/filename.ext
        get("/:id", (request, response) -> {
            PreparedStatement statement = connection.prepareStatement("SELECT `filename` FROM `files` WHERE `id` = ? LIMIT 1");
            statement.setString(1, request.params("id"));
            ResultSet result = statement.executeQuery();
            if (result.next()) {
                response.redirect("/" + request.params(":id") + "/" + result.getString("filename"), 301);
            } else {
                halt(404, "Not found");
            }
            return null;
        });
        // serve files from db
        get("/:id/*", (request, response) -> {
            PreparedStatement statement = connection.prepareStatement("SELECT `filename`, `type`, `data` FROM `files` WHERE `id` = ? LIMIT 1");
            statement.setString(1, request.params(":id"));
            ResultSet result = statement.executeQuery();
            if (result.next()) {
                if (request.splat().length == 0 || !result.getString("filename").equals(request.splat()[0])) {
                    // redirect to correct filename
                    response.redirect("/" + request.params(":id") + "/" + result.getString("filename"));
                    return null;
                }

                response.status(200);
                InputStream data = result.getBlob("data").getBinaryStream();
                response.type(result.getString("type"));
                IOUtils.copy(data, response.raw().getOutputStream());
                response.raw().getOutputStream().flush();
                response.raw().getOutputStream().close();
                return response.raw();
            } else {
                halt(404, "Not found");
                response.status(404);
                return "404";
            }
        });

        // hit counting
        after("/:id/*", (request, response) -> {
            if (response.status() == 200) {
                PreparedStatement statement = connection.prepareStatement("SELECT `hits` FROM `files` WHERE `id` = ?");
                statement.setString(1, request.params(":id"));
                ResultSet result = statement.executeQuery();
                if (result.next()) {
                    statement = connection.prepareStatement("UPDATE `files` SET `hits` = `hits` + 1 WHERE `id` = ?");
                    statement.setString(1, request.params(":id"));
                    statement.executeUpdate();
                }
            }
        });

        // file uploading
        put("/", (request, response) -> {
            request.attribute("org.eclipse.jetty.multipartConfig", new MultipartConfigElement("/temp"));

            String givenKey = request.raw().getPart("key") != null
                    ? IOUtils.toString(request.raw().getPart("key").getInputStream())
                    : null;
            if (StringUtils.isBlank(givenKey) || !givenKey.equals(key)) {
                halt(403, "Forbidden");
            }

            try (InputStream input = request.raw().getPart("file").getInputStream()) {
                String fileName = request.raw().getPart("file").getSubmittedFileName();
                String type = request.raw().getPart("file").getContentType();
                String id = RandomStringUtils.randomAlphabetic(6);

                PreparedStatement statement = connection.prepareStatement("INSERT INTO `files` (`id`, `filename`, `type`, `data`) VALUES (?, ?, ?, ?)");
                statement.setString(1, id);
                statement.setString(2, fileName);
                statement.setString(3, type);
                statement.setBlob(4, input);
                statement.executeUpdate();

                return request.url() + id + "/" + fileName;
            }
        });
    }

    private void readConsole() {
        console = LineReaderBuilder.builder()
                .appName("ShareServer")
                .build();
        while (true) {
            String line;
            try {
                line = console.readLine("share> ");
            } catch (UserInterruptException e) {
                shutdown();
                return;
            } catch (EndOfFileException e) {
                return;
            }

            String[] split = line.split(" ", 2);
            String command = split[0].toLowerCase();
            String[] args = split.length > 1 ? ArrayUtils.subarray(split, 1, split.length) : new String[0];
            String argsJoined = split.length == 2 ? split[1] : "";

            try {
                command:
                switch (command) {
                    case "exit":
                    case "stop":
                    case "quit":
                    case "end":
                        Runtime.getRuntime().removeShutdownHook(shutdownHook);
                        shutdown();
                        return;
                    case "echo":
                        log(String.join(" ", args));
                        break;
                    case "top": {
                        int amount = args.length >= 1 ? Integer.parseInt(args[0]) : 25;
                        ResultSet result = connection.createStatement().executeQuery("select id, filename, hits, type from files order by hits desc limit " + amount);
                        if (result.isBeforeFirst()) {
                            while (result.next()) {
                                String id = result.getString("id");
                                String filename = result.getString("filename");
                                int hits = result.getInt("hits");
                                log("File " + id + "/" + filename + " - " + hits + " hits");
                            }
                        } else {
                            log("No files in database");
                        }
                        break;
                    }
                    case "sql":
                    case "sqlf": {
                        boolean full = command.equals("sqlf");
                        Statement statement = connection.createStatement();
                        if (statement.execute(argsJoined)) {
                            ResultSet result = statement.getResultSet();
                            ResultSetMetaData meta = result.getMetaData();
                            int columns = meta.getColumnCount();
                            int row;
                            while (result.next()) {
                                row = result.getRow();
                                if (row > 100 && !full) {
                                    log("<more results, limit or use sqlf to show all>");
                                    break;
                                }

                                StringBuilder builder = new StringBuilder();
                                for (int i = 1; i <= columns; i++) {
                                    if (i > 1) builder.append(" | ");
                                    String columnValue = result.getString(i);
                                    builder.append(meta.getColumnName(i))
                                            .append("=")
                                            .append(columnValue);
                                }
                                log(builder);
                            }
                        }
                        break;
                    }
                    case "rm":
                    case "del":
                    case "delete": {
                        Set<String> targets = new HashSet<>();
                        Collections.addAll(targets, args);
                        while (targets.size() == 0) {
                            try {
                                Arrays.stream(console.readLine("file(s) to delete> ").split("[, ]"))
                                        .filter(StringUtils::isAlphanumeric)
                                        .forEach(targets::add);
                            } catch (UserInterruptException | EndOfFileException e) {
                                break command;
                            }
                        }
                        for (String target : targets) {
                            PreparedStatement s = connection.prepareStatement("delete from files where id like ?");
                            s.setString(1, "%" + target + "%");
                            int rows = s.executeUpdate();
                            log("Deleting " + target + " -> " + rows + " affected rows");
                        }
                        break;
                    }
                    default:
                        log("Unknown command");
                        break;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void log(Object o) {
        console.printAbove(o.toString());
    }

}
