import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * GoPDFGenie E2E Tester (Java 11+)
 *
 * Simulates FREE/STARTER/BUSINESS/GUEST usage against:
 *  - POST /api/v1/convert/url/async         (JSON)
 *  - POST /api/v1/convert/async             (multipart: file)
 *  - GET  /api/v1/jobs/{jobId}/status
 *  - GET  /api/v1/jobs/{jobId}/result
 *
 * New defaults:
 *   DEFAULT_URL        = https://www.youtube.com
 *   DEFAULT_INPUT_DIR  = ./input
 *   DEFAULT_OUTPUT_DIR = ./output
 *
 * CLI overrides:
 *   --url=..., --inDir=..., --outDir=..., --file=..., --out=...
 *
 * If no API key is provided, the tester auto-runs as GUEST (no Authorization header).
 */
public class E2ETester {

  // ======= OPTIONAL: paste plan keys here (or pass --apiKey=...) =======
  private static final String KEY_FREE     = "";
  private static final String KEY_STARTER  = "";
  private static final String KEY_BUSINESS = "";
  private static final String KEY_GUEST    = ""; // not used; guest = no token

  // Optional (RapidAPI proxy secret). Leave empty if not used; can also pass --rapidSecret=...
  private static final String RAPIDAPI_PROXY_SECRET_CONST = "";

  // ======= New defaults =======
  private static final String DEFAULT_URL        = "https://www.youtube.com";
  private static final String DEFAULT_INPUT_DIR  = "./input";
  private static final String DEFAULT_OUTPUT_DIR = "./output";

  public static void main(String[] args) throws Exception {
    Map<String, String> opt = parseArgs(args);

    String plan    = opt.getOrDefault("plan", "FREE").toUpperCase(Locale.ROOT);
    String mode    = opt.getOrDefault("mode", "url").toLowerCase(Locale.ROOT); // url|upload
    String apiBase = opt.getOrDefault("apiBase", "https://gopdfgenie.com/api/v1");

    // Query params supported by your controllers
    String outputFormat = opt.getOrDefault("outputFormat", "pdf");        // pdf|png
    String pageSize     = opt.getOrDefault("pageSize", "A4");             // Long|A4|A5|Letter|Legal|Tabloid
    String orientation  = opt.getOrDefault("orientation", "portrait");    // portrait|landscape
    String quality      = opt.getOrDefault("quality", "STANDARD");        // STANDARD|LOW|MEDIUM|HIGH

    long   pollMs   = Long.parseLong(opt.getOrDefault("pollMs", "2000"));
    int    maxPolls = Integer.parseInt(opt.getOrDefault("maxPolls", "60"));

    // New dir defaults (overridable)
    String inDir  = opt.getOrDefault("inDir", DEFAULT_INPUT_DIR);
    String outDir = opt.getOrDefault("outDir", DEFAULT_OUTPUT_DIR);

    // API key resolution (no env): CLI override wins; else plan constant
    String apiKey = opt.get("apiKey");
    if (isBlank(apiKey)) {
      apiKey = keyForPlan(plan);
    }

    // If still missing -> auto-fallback to Guest (no Authorization header)
    boolean guestMode = isBlank(apiKey) || apiKey.startsWith("PASTE_");
    if (guestMode) {
      plan = "GUEST";
      apiKey = null; // ensure we don't send Authorization
    }

    // RapidAPI secret (optional)
    String rapidSecret = opt.getOrDefault("rapidSecret", RAPIDAPI_PROXY_SECRET_CONST);
    if (isBlank(rapidSecret)) rapidSecret = null;

    System.out.printf("Plan=%s  Mode=%s  Base=%s  Output=%s Page=%s Ori=%s Q=%s%n",
      plan, mode, apiBase, outputFormat, pageSize, orientation, quality);
    if (apiKey != null) System.out.println("API Key: " + mask(apiKey));
    else System.out.println("Guest mode (no Authorization header).");
    System.out.println("Input dir:  " + Paths.get(inDir).toAbsolutePath());
    System.out.println("Output dir: " + Paths.get(outDir).toAbsolutePath());
    if (rapidSecret != null) System.out.println("RapidAPI proxy secret provided.");

    HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build();

    // 1) Submit job
    String jobId;
    if ("url".equals(mode)) {
      // Default URL if not provided
      String url = opt.getOrDefault("url", DEFAULT_URL);
      System.out.println("Using URL: " + url);
      jobId = submitUrlJob(http, apiBase, apiKey, rapidSecret, url, outputFormat, pageSize, orientation, quality);
    } else if ("upload".equals(mode)) {
      // If --file not provided, use <inDir>/index.html
      String fileArg = opt.get("file");
      Path filePath = (fileArg != null && !fileArg.isBlank())
          ? Paths.get(fileArg)
          : Paths.get(inDir, "index.html");
      System.out.println("Using upload file: " + filePath.toAbsolutePath());
      jobId = submitUploadJob(http, apiBase, apiKey, rapidSecret, filePath, outputFormat, pageSize, orientation, quality);
    } else {
      die("Unknown --mode=" + mode + " (use url|upload)");
      return;
    }
    System.out.println("Job ID: " + jobId);

    // 2) Poll
    String status = null;
    for (int i = 0; i < maxPolls; i++) {
      Thread.sleep(pollMs);
      status = getStatus(http, apiBase, apiKey, rapidSecret, jobId);
      System.out.println("Status: " + status);
      if ("COMPLETED".equalsIgnoreCase(status)) break;
      if ("FAILED".equalsIgnoreCase(status)) die("Conversion failed. Job=" + jobId);
    }
    if (!"COMPLETED".equalsIgnoreCase(status)) {
      die("Timeout waiting for COMPLETED. Job=" + jobId);
    }

    // 3) Download → save under outDir
    Path outPath = resolveOutputPath(opt.get("out"), outDir, outputFormat);
    Files.createDirectories(outPath.getParent());
    downloadResult(http, apiBase, apiKey, rapidSecret, jobId, outPath);
    System.out.println("Saved → " + outPath.toAbsolutePath());
  }

  // Resolve API key from the constants by plan
  private static String keyForPlan(String plan) {
    switch (plan) {
      case "FREE":     return KEY_FREE;
      case "STARTER":  return KEY_STARTER;
      case "BUSINESS": return KEY_BUSINESS;
      case "GUEST":    return KEY_GUEST; // usually empty
      default:         return "";
    }
  }

  // ---------- Submit URL job (POST /convert/url/async) ----------
  static String submitUrlJob(HttpClient http, String base, String key, String rapidSecret,
                             String targetUrl, String outputFmt, String pageSize, String orientation, String quality) throws Exception {
    String endpoint = String.format(
      "%s/convert/url/async?orientation=%s&outputFormat=%s&pageSize=%s&quality=%s",
      base, enc(orientation), enc(outputFmt), enc(pageSize), enc(quality)
    );
    String json = "{\"url\":\"" + escapeJson(targetUrl) + "\"}";
    HttpRequest.Builder b = HttpRequest.newBuilder()
        .uri(URI.create(endpoint))
        .timeout(Duration.ofSeconds(30))
        .header("Content-Type", "application/json");
    addHeaders(b, key, rapidSecret);
    HttpResponse<String> res = http.send(b.POST(HttpRequest.BodyPublishers.ofString(json)).build(), BodyHandlers.ofString());
    must2xx(res, "Submit URL");
    String jobId = jsonGet(res.body(), "jobId");
    if (jobId == null) die("No jobId in response: " + res.body());
    return jobId;
  }

  // ---------- Submit upload job (POST /convert/async) ----------
  static String submitUploadJob(HttpClient http, String base, String key, String rapidSecret,
                                Path filePath, String outputFmt, String pageSize, String orientation, String quality) throws Exception {
    String endpoint = String.format(
      "%s/convert/async?orientation=%s&outputFormat=%s&pageSize=%s&quality=%s",
      base, enc(orientation), enc(outputFmt), enc(pageSize), enc(quality)
    );
    if (!Files.exists(filePath)) die("File not found: " + filePath);

    // Build multipart/form-data with single 'file' part
    String boundary = "----GoPDFGenieBoundary" + System.currentTimeMillis();
    String contentType = inferContentType(filePath); // text/html or application/zip
    byte[] fileBytes = Files.readAllBytes(filePath);

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    PrintWriter pw = new PrintWriter(new OutputStreamWriter(baos, java.nio.charset.StandardCharsets.UTF_8), true);

    // file part
    pw.printf("--%s\r\n", boundary);
    pw.printf("Content-Disposition: form-data; name=\"file\"; filename=\"%s\"\r\n", filePath.getFileName());
    pw.printf("Content-Type: %s\r\n\r\n", contentType);
    pw.flush();
    baos.write(fileBytes);
    baos.write("\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    // end
    pw.printf("--%s--\r\n", boundary);
    pw.flush();

    HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.ofByteArray(baos.toByteArray());
    HttpRequest.Builder b = HttpRequest.newBuilder()
        .uri(URI.create(endpoint))
        .timeout(Duration.ofSeconds(60))
        .header("Content-Type", "multipart/form-data; boundary=" + boundary);
    addHeaders(b, key, rapidSecret);

    HttpResponse<String> res = http.send(b.POST(body).build(), BodyHandlers.ofString());
    must2xx(res, "Submit Upload");
    String jobId = jsonGet(res.body(), "jobId");
    if (jobId == null) die("No jobId in response: " + res.body());
    return jobId;
  }

  // ---------- Status (GET /jobs/{jobId}/status) ----------
  static String getStatus(HttpClient http, String base, String key, String rapidSecret, String jobId) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder()
        .uri(URI.create(base + "/jobs/" + enc(jobId) + "/status"))
        .timeout(Duration.ofSeconds(20));
    addHeaders(b, key, rapidSecret);
    HttpResponse<String> res = http.send(b.GET().build(), BodyHandlers.ofString());
    must2xx(res, "Status");
    String status = jsonGet(res.body(), "status");
    return status != null ? status : "UNKNOWN";
  }

  // ---------- Download (GET /jobs/{jobId}/result) ----------
  static void downloadResult(HttpClient http, String base, String key, String rapidSecret, String jobId, Path out) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder()
        .uri(URI.create(base + "/jobs/" + enc(jobId) + "/result"))
        .timeout(Duration.ofSeconds(60));
    addHeaders(b, key, rapidSecret);
    HttpResponse<byte[]> res = http.send(b.GET().build(), BodyHandlers.ofByteArray());
    if (res.statusCode() == 202) die("Job still processing (202). Try polling longer.");
    must2xx(res, "Download");
    Files.write(out, res.body());
  }

  // ---------- Header helper ----------
  static void addHeaders(HttpRequest.Builder b, String apiKey, String rapidSecret) {
    if (!isBlank(apiKey)) {
      b.header("Authorization", "Bearer " + apiKey);
    }
    if (!isBlank(rapidSecret)) {
      b.header("X-RapidAPI-Proxy-Secret", rapidSecret);
    }
  }

  // ---------- Helpers ----------
  static Map<String, String> parseArgs(String[] args) {
    Map<String, String> m = new HashMap<>();
    for (String a : args) {
      if (a.startsWith("--") && a.contains("=")) {
        int i = a.indexOf('=');
        m.put(a.substring(2, i), a.substring(i + 1));
      }
    }
    return m;
  }

  static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }

  static void must2xx(HttpResponse<?> res, String label) {
    int s = res.statusCode();
    if (s < 200 || s >= 300) {
      String body = "";
      try { body = res.body() == null ? "" : res.body().toString(); } catch (Exception ignore) {}
      die(label + " failed: HTTP " + s + " " + body);
    }
  }

  static void die(String msg) {
    System.err.println(msg);
    System.exit(2);
  }

  static String enc(String s) { return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8); }

  static String escapeJson(String s) {
    if (s == null) return "";
    StringBuilder sb = new StringBuilder(s.length() + 16);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\"': sb.append("\\\""); break;
        case '\\': sb.append("\\\\"); break;
        case '\b': sb.append("\\b"); break;
        case '\f': sb.append("\\f"); break;
        case '\n': sb.append("\\n"); break;
        case '\r': sb.append("\\r"); break;
        case '\t': sb.append("\\t"); break;
        default:
          if (c < 0x20) sb.append(String.format("\\u%04x", (int)c));
          else sb.append(c);
      }
    }
    return sb.toString();
  }

  static String jsonGet(String json, String key) {
    // naive extraction for demo; use Jackson/Gson in real apps
    String needle = "\"" + key + "\"";
    int i = json.indexOf(needle);
    if (i < 0) return null;
    int colon = json.indexOf(":", i);
    if (colon < 0) return null;
    int q1 = json.indexOf('"', colon + 1);
    int q2 = q1 >= 0 ? json.indexOf('"', q1 + 1) : -1;
    if (q1 >= 0 && q2 > q1) return json.substring(q1 + 1, q2);
    int comma = json.indexOf(',', colon + 1);
    int end = comma > 0 ? comma : json.indexOf('}', colon + 1);
    if (end > colon) return json.substring(colon + 1, end).trim().replaceAll("[{}\\s\"]", "");
    return null;
  }

  static String inferContentType(Path p) {
    String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
    if (name.endsWith(".zip")) return "application/zip";
    if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html; charset=utf-8";
    return "application/octet-stream";
  }

  static Path resolveOutputPath(String outArg, String outDir, String outputFormat) {
    String fname = (!isBlank(outArg)) ? outArg : ("output." + ("png".equalsIgnoreCase(outputFormat) ? "png" : "pdf"));
    Path p = Paths.get(fname);
    if (p.isAbsolute() || p.getParent() != null) return p; // user gave path or folder
    return Paths.get(outDir, fname);
  }

  static String mask(String s) {
    if (s == null || s.length() < 8) return "****";
    return s.substring(0, 4) + "****" + s.substring(s.length() - 4);
  }
}
