import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;


public class YandexWeatherClient {
    public static void main(String[] args) {
        String defaultLat = "55.75";
        String defaultLon = "37.62";
        int defaultLimit = 5;

        String apiKey = null;
        String lat = defaultLat;
        String lon = defaultLon;
        int limit = defaultLimit;

        apiKey = "de8962b9-8e59-422e-bb88-1b963b728b21";
    

        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Ошибка: не указан API ключ Yandex Weather.");
            System.err.println("Укажите ключ как первый аргумент или в переменной окружения YANDEX_WEATHER_KEY.");
            System.exit(2);
        }

        try {
            String uri = String.format("https://api.weather.yandex.ru/v2/forecast?lat=%s&lon=%s&limit=%d",
                    lat, lon, limit);

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(uri))
                    .header("X-Yandex-Weather-Key", apiKey)
                    .GET()
                    .build();

            System.out.println("Отправляю GET: " + uri);
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();
            String body = response.body();

            System.out.println("HTTP status: " + status);
            System.out.println("------ Полный JSON ответ от сервиса ------");
            System.out.println(body);
            System.out.println("------ Конец JSON ------");

            // Парсим JSON с помощью встроенного небольшого парсера (внизу)
            Object parsed = Json.parse(body);
            if (!(parsed instanceof Map)) {
                System.err.println("Неожиданный формат JSON (ожидался объект).");
                System.exit(3);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> json = (Map<String, Object>) parsed;

            // Извлечь fact.temp
            Double factTemp = null;
            Object factObj = json.get("fact");
            if (factObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> factMap = (Map<String, Object>) factObj;
                factTemp = asDouble(factMap.get("temp"));
            }

            if (factTemp != null) {
                System.out.printf("Текущая температура (fact.temp): %.2f°C%n", factTemp);
            } else {
                System.out.println("fact.temp не найден в ответе.");
            }

            // Извлечь forecasts[*].parts.day.temp_avg для первых 'limit' forecast-ов
            Object forecastsObj = json.get("forecasts");
            if (forecastsObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> forecasts = (List<Object>) forecastsObj;
                int take = Math.min(limit, forecasts.size());
                double sum = 0.0;
                int count = 0;
                for (int i = 0; i < take; i++) {
                    Object f = forecasts.get(i);
                    if (!(f instanceof Map)) {
                        System.out.println(String.format("День %d: прогноз имеет неожиданный формат", i+1));
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> fMap = (Map<String, Object>) f;
                    Object partsObj = fMap.get("parts");
                    if (partsObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> partsMap = (Map<String, Object>) partsObj;
                        Object dayObj = partsMap.get("day");
                        if (dayObj instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> dayMap = (Map<String, Object>) dayObj;
                            Double tempAvg = asDouble(dayMap.get("temp_avg"));
                            if (tempAvg != null) {
                                sum += tempAvg;
                                count++;
                                System.out.println(String.format("День %d: parts.day.temp_avg = %.2f°C", i+1, tempAvg));
                            } else {
                                System.out.println(String.format("День %d: parts.day.temp_avg отсутствует", i+1));
                            }
                        } else {
                            System.out.println(String.format("День %d: parts.day отсутствует", i+1));
                        }
                    } else {
                        System.out.println(String.format("День %d: parts отсутствует", i+1));
                    }
                }
                if (count > 0) {
                    double avg = sum / count;
                    System.out.println(String.format("Средняя temp_avg по первым %d суткам (limit=%d): %.2f°C", count, limit, avg));
                } else {
                    System.out.println("Не найдено ни одного parts.day.temp_avg для вычисления среднего.");
                }
            } else {
                System.out.println("Поле forecasts отсутствует или имеет неожиданный формат.");
            }

        } catch (Exception e) {
            System.err.println("Ошибка при выполнении запроса/парсинга: " + e.getMessage());
            e.printStackTrace();
            System.exit(5);
        }
    }

    private static Double asDouble(Object o) {
        if (o == null) return null;
        if (o instanceof Number) {
            return ((Number) o).doubleValue();
        }
        if (o instanceof String) {
            try {
                return Double.parseDouble((String) o);
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    static class Json {
        static Object parse(String s) throws JsonParseException {
            Parser p = new Parser(s);
            Object v = p.parseValue();
            p.skipWhitespace();
            if (!p.isEnd()) throw new JsonParseException("Доп. символы после JSON");
            return v;
        }

        static final class Parser {
            private final String s;
            private final int len;
            private int pos;

            Parser(String s) {
                this.s = s;
                this.len = s.length();
                this.pos = 0;
            }

            boolean isEnd() { return pos >= len; }

            void skipWhitespace() {
                while (!isEnd()) {
                    char c = s.charAt(pos);
                    if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++;
                    else break;
                }
            }

            Object parseValue() {
                skipWhitespace();
                if (isEnd()) throw new JsonParseException("Ожидалось значение, но встретился конец строки");
                char c = s.charAt(pos);
                if (c == '{') return parseObject();
                if (c == '[') return parseArray();
                if (c == '"') return parseString();
                if (c == 't') { expectLiteral("true"); return Boolean.TRUE; }
                if (c == 'f') { expectLiteral("false"); return Boolean.FALSE; }
                if (c == 'n') { expectLiteral("null"); return null; }
                // number
                return parseNumber();
            }

            void expectLiteral(String lit) {
                int l = lit.length();
                if (pos + l > len) throw new JsonParseException("Ожидалось " + lit);
                String sub = s.substring(pos, pos + l);
                if (!sub.equals(lit)) throw new JsonParseException("Ожидалось " + lit + " но найдено " + sub);
                pos += l;
            }

            Map<String, Object> parseObject() {
                if (s.charAt(pos) != '{') throw new JsonParseException("Ожидался '{' в позиции " + pos);
                pos++; // skip '{'
                skipWhitespace();
                Map<String, Object> map = new LinkedHashMap<>();
                if (pos < len && s.charAt(pos) == '}') { pos++; return map; }
                while (true) {
                    skipWhitespace();
                    if (pos >= len || s.charAt(pos) != '"') throw new JsonParseException("Ожидался ключ-строка объекта в позиции " + pos);
                    String key = parseString();
                    skipWhitespace();
                    if (pos >= len || s.charAt(pos) != ':') throw new JsonParseException("Ожидался ':' после ключа в позиции " + pos);
                    pos++; // skip ':'
                    Object value = parseValue();
                    map.put(key, value);
                    skipWhitespace();
                    if (pos >= len) throw new JsonParseException("Ожидался ',' или '}' в позиции " + pos);
                    char c = s.charAt(pos);
                    if (c == ',') { pos++; continue; }
                    if (c == '}') { pos++; break; }
                    throw new JsonParseException("Ожидался ',' или '}' в позиции " + pos + " но найден '" + c + "'");
                }
                return map;
            }

            List<Object> parseArray() {
                if (s.charAt(pos) != '[') throw new JsonParseException("Ожидался '[' в позиции " + pos);
                pos++; // skip '['
                skipWhitespace();
                List<Object> list = new ArrayList<>();
                if (pos < len && s.charAt(pos) == ']') { pos++; return list; }
                while (true) {
                    Object v = parseValue();
                    list.add(v);
                    skipWhitespace();
                    if (pos >= len) throw new JsonParseException("Ожидался ',' или ']' в позиции " + pos);
                    char c = s.charAt(pos);
                    if (c == ',') { pos++; continue; }
                    if (c == ']') { pos++; break; }
                    throw new JsonParseException("Ожидался ',' или ']' в позиции " + pos + " но найден '" + c + "'");
                }
                return list;
            }

            String parseString() {
                if (s.charAt(pos) != '"') throw new JsonParseException("Ожидался '\"' в позиции " + pos);
                pos++; // skip opening "
                StringBuilder sb = new StringBuilder();
                while (pos < len) {
                    char c = s.charAt(pos++);
                    if (c == '"') return sb.toString();
                    if (c == '\\') {
                        if (pos >= len) throw new JsonParseException("Неполная escape-последовательность");
                        char e = s.charAt(pos++);
                        switch (e) {
                            case '"': sb.append('"'); break;
                            case '\\': sb.append('\\'); break;
                            case '/': sb.append('/'); break;
                            case 'b': sb.append('\b'); break;
                            case 'f': sb.append('\f'); break;
                            case 'n': sb.append('\n'); break;
                            case 'r': sb.append('\r'); break;
                            case 't': sb.append('\t'); break;
                            case 'u':
                                if (pos + 4 > len) throw new JsonParseException("Неполная \\u escape-последовательность");
                                String hex = s.substring(pos, pos + 4);
                                pos += 4;
                                try {
                                    int code = Integer.parseInt(hex, 16);
                                    sb.append((char) code);
                                } catch (NumberFormatException ex) {
                                    throw new JsonParseException("Неверный \\uXXXX: " + hex);
                                }
                                break;
                            default:
                                throw new JsonParseException("Неизвестная escape-последовательность: \\" + e);
                        }
                    } else {
                        sb.append(c);
                    }
                }
                throw new JsonParseException("Незавершённая строка (открытая кавычка)");
            }

            Object parseNumber() {
                int start = pos;
                if (s.charAt(pos) == '-') pos++;
                boolean hasDigits = false;
                while (pos < len) {
                    char c = s.charAt(pos);
                    if (c >= '0' && c <= '9') { pos++; hasDigits = true; }
                    else break;
                }
                if (!hasDigits) throw new JsonParseException("Ожидалось число в позиции " + start);
                if (pos < len && s.charAt(pos) == '.') {
                    pos++;
                    if (pos >= len || !Character.isDigit(s.charAt(pos))) throw new JsonParseException("Ожидалось цифру после десятичной точки");
                    while (pos < len && Character.isDigit(s.charAt(pos))) pos++;
                }
                if (pos < len) {
                    char c = s.charAt(pos);
                    if (c == 'e' || c == 'E') {
                        pos++;
                        if (pos < len && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
                        if (pos >= len || !Character.isDigit(s.charAt(pos))) throw new JsonParseException("Ожидалась экспонента числа");
                        while (pos < len && Character.isDigit(s.charAt(pos))) pos++;
                    }
                }
                String numStr = s.substring(start, pos);
                try {
                    // Попробуем вернуть Integer/Long если возможно, иначе Double
                    if (numStr.indexOf('.') < 0 && numStr.indexOf('e') < 0 && numStr.indexOf('E') < 0) {
                        try {
                            long lv = Long.parseLong(numStr);
                            if (lv >= Integer.MIN_VALUE && lv <= Integer.MAX_VALUE) return (int) lv;
                            return lv;
                        } catch (NumberFormatException ex) {
                            // fallthrough
                        }
                    }
                    Double d = Double.parseDouble(numStr);
                    return d;
                } catch (NumberFormatException ex) {
                    throw new JsonParseException("Невозможно распарсить число: " + numStr);
                }
            }
        }

        static class JsonParseException extends RuntimeException {
            JsonParseException(String msg) { super(msg); }
        }
    }
}
