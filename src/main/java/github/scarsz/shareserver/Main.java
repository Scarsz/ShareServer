package github.scarsz.shareserver;

import org.apache.commons.lang3.RandomStringUtils;
import spark.utils.IOUtils;
import spark.utils.StringUtils;

import javax.servlet.MultipartConfigElement;
import java.io.File;
import java.io.InputStream;
import java.sql.*;

import static spark.Spark.*;

public class Main {

    public static void main(String[] args) throws SQLException {
        String key = args.length != 0 ? args[0] : null;
        if (key == null) {
            System.err.println("No key given");
            return;
        }

        Connection connection = DriverManager.getConnection("jdbc:h2:" + new File("share").getAbsolutePath());
        connection.prepareStatement("CREATE TABLE IF NOT EXISTS `files` (`id` VARCHAR NOT NULL, `filename` VARCHAR NOT NULL, `type` VARCHAR NOT NULL, `data` BLOB NOT NULL, PRIMARY KEY (`id`), UNIQUE KEY id (`id`))").executeUpdate();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                connection.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }));

        port(8082);
//        staticFiles.location("/static");

        // gzip where possible
        after((request, response) -> response.header("Content-Encoding", "gzip"));

        // logging
        afterAfter((request, response) -> {
            String ip = request.ip();
            String method = request.requestMethod();
            String location = request.url() + (StringUtils.isNotBlank(request.queryString()) ? "?" + request.queryString() : "");
            System.out.println(ip + " " + method + " " + location + " -> " + response.status());
        });

        get("/:id/*", (request, response) -> {
            PreparedStatement statement = connection.prepareStatement("SELECT `filename`, `type`, `data` FROM `files` WHERE `id` = ? LIMIT 1");
            statement.setString(1, request.params(":id"));
            ResultSet result = statement.executeQuery();
            if (result.next()) {
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
