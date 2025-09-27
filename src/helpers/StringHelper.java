package helpers;

public class StringHelper {

    public static String capitalizeWords(String input) {
        if (input == null || input.isEmpty()) return input;

        String[] words = input.toLowerCase().trim().split("\\s+");
        StringBuilder result = new StringBuilder();

        for (String word : words) {
            if (word.length() > 0) {
                result.append(Character.toUpperCase(word.charAt(0)))
                      .append(word.substring(1))
                      .append(" ");
            }
        }

        return result.toString().trim();
    }
}
