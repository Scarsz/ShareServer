package github.scarsz.shareserver;

import org.apache.commons.lang3.RandomStringUtils;
import spark.utils.IOUtils;
import spark.utils.StringUtils;

import javax.servlet.MultipartConfigElement;
import java.io.File;
import java.io.InputStream;
import java.sql.*;

import static spark.Spark.*;

public class ShareServer {

    private final Connection connection;

    public ShareServer(String key, int port) throws SQLException {
        if (key == null) throw new IllegalArgumentException("No key given");

        connection = DriverManager.getConnection("jdbc:h2:" + new File("share").getAbsolutePath());
        connection.prepareStatement("CREATE TABLE IF NOT EXISTS `files` (" +
                "`id` VARCHAR NOT NULL, " +
                "`filename` VARCHAR NOT NULL, " +
                "`hits` INT NOT NULL DEFAULT 0, " +
                "`type` VARCHAR NOT NULL, " +
                "`data` BLOB NOT NULL, " +
                "PRIMARY KEY (`id`), UNIQUE KEY id (`id`))").executeUpdate();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                connection.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }));

        port(port);

        // gzip where possible
        after((request, response) -> response.header("Content-Encoding", "gzip"));

        // logging
        afterAfter((request, response) -> {
            String forwardedFor = request.headers("X-Forwarded-For");
            String ip = StringUtils.isNotBlank(forwardedFor) ? forwardedFor : request.ip();
            String method = request.requestMethod();
            String location = request.url() + (StringUtils.isNotBlank(request.queryString()) ? "?" + request.queryString() : "");
            System.out.println(ip + ":" + request.raw().getRemotePort() + " " + method + " " + location + " -> " + response.status());
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
                    System.out.println(request.params(":id") + " is now at " + (result.getInt("hits") + 1) + " hits");
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

}
