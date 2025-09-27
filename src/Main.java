import io.github.cdimascio.dotenv.Dotenv;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.net.InetSocketAddress;
import org.mindrot.jbcrypt.BCrypt;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonSerializer;
import com.google.gson.JsonPrimitive;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import com.google.gson.reflect.TypeToken;
import java.sql.*;
import java.util.*;
import helpers.StringHelper;

public class Main {
    private static Dotenv dotenv = Dotenv.load();

    private static Gson gson = new GsonBuilder()
            .registerTypeAdapter(LocalDateTime.class,
                    (JsonSerializer<LocalDateTime>) (src, typeOfSrc,
                            context) -> new JsonPrimitive(src.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)))
            .registerTypeAdapter(LocalDateTime.class,
                    (JsonDeserializer<LocalDateTime>) (json, type, context) -> LocalDateTime.parse(json.getAsString(),
                            DateTimeFormatter.ISO_LOCAL_DATE_TIME))
            .create();

    public static void main(String[] args) throws Exception {

        int port = Integer.parseInt(dotenv.get("SERVER_PORT"));

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String path = exchange.getRequestURI().getPath();
            switch (path) {
                case "/login":
                    handleLogin(exchange);
                    break;
                case "/employees":
                    handleEmployees(exchange);
                    break;
                default:
                    if (path.startsWith("/employees/")) {
                        handleEmployeeById(exchange);
                    } else {
                        sendResponse(exchange, 404, "{\"error\":\"Not found\"}");
                    }
                    break;
            }
        });

        server.setExecutor(null);
        server.start();
        System.out.println(
                "Server started at http://" + dotenv.get("SERVER_DOMAIN") + ":" + dotenv.get("SERVER_PORT") + "/");
    }

    private static void handleLogin(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }

        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> params = gson.fromJson(body, new TypeToken<Map<String, String>>() {
            }.getType());

            String email = params.get("username");
            String password = params.get("password");

            try (Connection conn = getConnection();
                    PreparedStatement stmt = conn.prepareStatement(
                            "SELECT userId, email, password, roleId, CONCAT(firstname, ' ', lastname) AS `name` " +
                                    "FROM tbl_users WHERE email=?")) {
                stmt.setString(1, email);
                ResultSet rs = stmt.executeQuery();

                if (rs.next()) {
                    String hashedPassword = rs.getString("password");
                    if (hashedPassword.startsWith("$2y$")) {
                        hashedPassword = "$2a$" + hashedPassword.substring(4);
                    }
                    if (BCrypt.checkpw(password, hashedPassword)) {
                        Map<String, Object> resp = new HashMap<>();
                        resp.put("success", true);
                        resp.put("userId", rs.getInt("userId"));
                        resp.put("name", StringHelper.capi capitalizeWords(rs.getString("name")));
                        resp.put("email", rs.getString("email"));
                        String roleId = rs.getString("roleId");
                        resp.put("role", "1".equals(roleId) ? "admin" : "viewer");
                        sendResponse(exchange, 200, gson.toJson(resp));
                        System.out.println("[" + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                                + "] User " + email + " logged in successfully.");
                        return;
                    }
                }
                System.out.println("[" + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                        + "] Failed login attempt for user " + email);
                sendResponse(exchange, 401, "{\"success\":false,\"error\":\"Invalid credentials\"}");
            }

        } catch (

        Exception e) {
            System.err.println("[" + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    + "] Error during login: " + e.getMessage());
            sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private static void handleEmployees(HttpExchange exchange) throws IOException {
        try {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                try (Connection conn = getConnection();
                        PreparedStatement stmt = conn.prepareStatement("SELECT * FROM tbl_employee");
                        ResultSet rs = stmt.executeQuery()) {

                    List<Map<String, Object>> employees = new ArrayList<>();
                    ResultSetMetaData meta = rs.getMetaData();
                    int columnCount = meta.getColumnCount();

                    while (rs.next()) {
                        Map<String, Object> emp = new HashMap<>();
                        for (int i = 1; i <= columnCount; i++) {
                            String columnName = meta.getColumnName(i);
                            int columnType = meta.getColumnType(i);
                            Object value = rs.getObject(i);
                            switch (columnType) {
                                case java.sql.Types.DATE:
                                    if (value != null) {
                                        java.sql.Date date = (java.sql.Date) value;
                                        emp.put(columnName, date.toLocalDate().toString()); // yyyy-MM-dd
                                    } else {
                                        emp.put(columnName, null);
                                    }
                                    break;
                                default:
                                    emp.put(columnName, value);
                            }
                        }
                        employees.add(emp);
                    }

                    sendResponse(exchange, 200, gson.toJson(employees));

                } catch (SQLException e) {
                    e.printStackTrace();
                    sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
                }
            } else if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, String> params = gson.fromJson(body, new TypeToken<Map<String, String>>() {
                }.getType());

                try (Connection conn = getConnection();
                        PreparedStatement stmt = conn.prepareStatement(
                                "INSERT INTO tbl_employee(name,email) VALUES (?,?)", Statement.RETURN_GENERATED_KEYS)) {

                    stmt.setString(1, params.get("name"));
                    stmt.setString(2, params.get("email"));
                    int affected = stmt.executeUpdate();

                    if (affected > 0) {
                        ResultSet keys = stmt.getGeneratedKeys();
                        keys.next();
                        int id = keys.getInt(1);
                        Map<String, Object> emp = new HashMap<>();
                        emp.put("id", id);
                        emp.put("name", params.get("name"));
                        emp.put("email", params.get("email"));
                        sendResponse(exchange, 200, gson.toJson(emp));
                    } else {
                        sendResponse(exchange, 500, "{\"error\":\"Failed to create employee\"}");
                    }
                }
            } else {
                sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            }
        } catch (Exception e) {
            sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    // Handle /employees/:id (GET, PUT, DELETE)
    private static void handleEmployeeById(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String idStr = path.substring("/employees/".length());
        int id;
        try {
            id = Integer.parseInt(idStr);
        } catch (NumberFormatException e) {
            sendResponse(exchange, 400, "{\"error\":\"Invalid id\"}");
            return;
        }

        try {
            if ("PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, String> params = gson.fromJson(body, new TypeToken<Map<String, String>>() {
                }.getType());

                // Only allow updating a single field per request
                if (params.isEmpty()) {
                    sendResponse(exchange, 400, "{\"error\":\"No fields to update\"}");
                    return;
                }
                String field = null;
                String value = null;
                for (Map.Entry<String, String> entry : params.entrySet()) {
                    if (!"refno".equalsIgnoreCase(entry.getKey())) {
                        field = entry.getKey();
                        value = entry.getValue();
                        break;
                    }
                }
                if (field == null) {
                    sendResponse(exchange, 400, "{\"error\":\"No updatable field provided\"}");
                    return;
                }
                String sqlQuery = "UPDATE tbl_employee SET lastname=?, firstname=? WHERE id=?";
                String lastname = params.getOrDefault("lastname", null);
                String firstname = params.getOrDefault("firstname", null);
                // Use id from path
                Map<String, Object> resp = new HashMap<>();
                System.out.println("SQL: " + sqlQuery);
                resp.put("sql", sqlQuery);
                if (lastname == null || firstname == null) {
                    resp.put("error", "Missing lastname or firstname in request body");
                    sendResponse(exchange, 400, gson.toJson(resp));
                    return;
                }
                try (Connection conn = getConnection();
                        PreparedStatement stmt = conn.prepareStatement(sqlQuery)) {
                    stmt.setString(1, lastname);
                    stmt.setString(2, firstname);
                    stmt.setInt(3, id);
                    int affected = stmt.executeUpdate();
                    resp.put("affected", affected);
                    if (affected > 0) {
                        resp.put("updated", Map.of("id", id, "lastname", lastname, "firstname", firstname));
                        sendResponse(exchange, 200, gson.toJson(resp));
                    } else {
                        resp.put("error", "Employee not found");
                        sendResponse(exchange, 404, gson.toJson(resp));
                    }
                }
            } else if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
                try (Connection conn = getConnection();
                        PreparedStatement stmt = conn.prepareStatement("DELETE FROM tbl_employee WHERE refno=?")) {

                    stmt.setInt(1, id);
                    int affected = stmt.executeUpdate();
                    if (affected > 0) {
                        sendResponse(exchange, 200, "{\"success\":true}");
                    } else {
                        sendResponse(exchange, 404, "{\"error\":\"Employee not found\"}");
                    }
                }
            } else {
                sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            }
        } catch (Exception e) {
            sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
        }
        return;
    }

    private static Connection getConnection() throws Exception {
        String url = dotenv.get("DB_URL");
        String user = dotenv.get("DB_USER");
        String password = dotenv.get("DB_PASS");
        Class.forName("com.mysql.cj.jdbc.Driver");
        return DriverManager.getConnection(url, user, password);
    }

    private static void sendResponse(HttpExchange exchange, int status, String response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, response.getBytes().length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response.getBytes());
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
