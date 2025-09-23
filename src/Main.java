import io.github.cdimascio.dotenv.Dotenv;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.net.InetSocketAddress;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

public class Main {
    private static Dotenv dotenv = Dotenv.load();

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(7000), 0);

        server.createContext("/users", exchange -> {
            String response;
            try (Connection conn = getConnection();
                    Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery("SELECT userId, name FROM tbl_users")) {

                List<String> users = new ArrayList<>();
                while (rs.next()) {
                    int id = rs.getInt("userId");
                    String name = rs.getString("name");
                    users.add("{\"userId\":" + id + ",\"name\":\"" + name + "\"}");
                }
                response = "[" + String.join(",", users) + "]";
            } catch (Exception e) {
                response = "{\"error\":\"" + e.getMessage() + "\"}";
            }

            sendResponse(exchange, 200, response);
        });

        server.createContext("/login", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> params = parseFormData(body);

            String username = params.get("username");
            String password = params.get("password");

            if (username == null || password == null) {
                sendResponse(exchange, 400, "{\"error\":\"Missing username or password\"}");
                return;
            }

            try (Connection conn = getConnection();
                    PreparedStatement stmt = conn.prepareStatement(
                            "SELECT userId, name FROM tbl_users WHERE name=? AND password=?")) {

                stmt.setString(1, username);
                stmt.setString(2, password);

                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    int id = rs.getInt("userId");
                    String name = rs.getString("name");
                    String response = String.format(
                            "{\"success\":true,\"userId\":%d,\"name\":\"%s\"}", id, name);
                    sendResponse(exchange, 200, response);
                } else {
                    sendResponse(exchange, 401,
                            "{\"success\":false,\"error\":\"Invalid credentials\"}");
                }
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        });

        System.out.println("Server started at http://localhost:7000/");
        server.start();
    }

    private static Connection getConnection() throws Exception {
        String url = dotenv.get("DB_URL");
        String user = dotenv.get("DB_USER");
        String password = dotenv.get("DB_PASS");
        Class.forName("com.mysql.cj.jdbc.Driver");
        return DriverManager.getConnection(url, user, password);
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String response) {
        try {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static Map<String, String> parseFormData(String body) {
        Map<String, String> params = new HashMap<>();
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                params.put(kv[0], java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return params;
    }
}
