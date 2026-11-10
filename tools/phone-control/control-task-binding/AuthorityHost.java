import com.deepseekharness.app.control.ControlTaskAuthority;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Host-only HTTP seam around the production pure authority; no Android capability is used. */
public final class AuthorityHost {
  private static final ControlTaskAuthority authority = new ControlTaskAuthority();
  private static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
  public static void main(String[] ignored) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      String result;
      try {
        if (!"fixture-token".equals(exchange.getRequestHeaders().getFirst("X-Token")))
          throw new IllegalStateException("UNAUTHORIZED");
        Map<String, String> args = new HashMap<>();
        String query = exchange.getRequestURI().getRawQuery();
        if (query != null) for (String item : query.split("&")) {
          String[] pair = item.split("=", 2);
          args.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
              URLDecoder.decode(pair.length == 2 ? pair[1] : "", StandardCharsets.UTF_8));
        }
        var binding = new ControlTaskAuthority.Binding(ControlTaskAuthority.Owner.LOCAL_DSH,
            "host-bridge-generation-1", args.get("taskSessionId"), args.get("turnId"));
        String operation = exchange.getRequestURI().getPath().substring("/app/ui/".length());
        synchronized (authority) {
          if (operation.equals("task-end")) {
            authority.completeLocal(binding); result = "OK";
          } else if (operation.equals("fixture-pause")) {
            authority.lease(binding); authority.pause("EXACT_TASK_PAUSE"); result = "OK";
          } else if (operation.equals("fixture-resume")) {
            authority.resume(); result = "OK";
          } else {
            if (args.containsKey("completedTurnId")) authority.completeLocal(
                new ControlTaskAuthority.Binding(binding.owner(), binding.connection(),
                    binding.taskSessionId(), args.get("completedTurnId")));
            var lease = authority.begin(binding);
            if (operation.equals("task-begin")) {
              result = "{\"ok\":true,\"state\":\"COMPLETED\",\"needsObservation\":true}";
            } else if (operation.equals("dump")) {
              authority.observed(lease);
              result = "{\"ok\":true,\"state\":\"COMPLETED\",\"sessionId\":"
                  + quote(binding.taskSessionId()) + ",\"turnId\":" + quote(binding.turnId()) + "}";
            } else if (!authority.actionAllowed(lease)) {
              result = "[ERR] FRESH_SCREEN_OBSERVATION_REQUIRED";
            } else {
              result = "{\"ok\":true,\"state\":\"COMPLETED\",\"effectVerified\":false}";
            }
          }
        }
      } catch (Exception failure) { result = "[ERR] " + failure.getMessage(); }
      byte[] bytes = ("{\"result\":" + quote(result) + "}").getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes); exchange.close();
    });
    server.start();
    System.out.println("PORT=" + server.getAddress().getPort());
    System.out.flush();
  }
}
