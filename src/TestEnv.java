import io.github.cdimascio.dotenv.Dotenv;

public class TestEnv {
    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure()
            .directory("./")      // explicitly set directory
            .filename(".env")     // explicitly set file
            .load();

        System.out.println("DB_USER: " + dotenv.get("DB_USER"));
        System.out.println("DB_PASS: " + dotenv.get("DB_PASS"));
        System.out.println("DB_URL: " + dotenv.get("DB_URL"));
    }
}
