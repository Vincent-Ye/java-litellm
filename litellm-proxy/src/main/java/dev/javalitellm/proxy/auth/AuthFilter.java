package dev.javalitellm.proxy.auth;

import dev.javalitellm.proxy.config.ProxyConfigLoader;
import dev.javalitellm.proxy.keys.KeyService;
import dev.javalitellm.proxy.keys.VirtualKey;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bearer auth for {@code /v1/**} (master key or virtual key) and {@code /key/**} (master key only).
 * The resolved identity is exposed as request attributes for controllers.
 */
@Component
public class AuthFilter extends OncePerRequestFilter {

    public static final String ATTR_IS_MASTER = "litellm.isMaster";
    public static final String ATTR_VIRTUAL_KEY = "litellm.virtualKey";

    private final String masterKey;
    private final KeyService keys;

    public AuthFilter(ProxyConfigLoader.LoadedConfig config, KeyService keys) {
        this.masterKey = config.masterKey();
        this.keys = keys;
    }

    private static final java.util.List<String> ADMIN_PREFIXES =
            java.util.List.of("/key/", "/spend/", "/team/", "/user/", "/model/");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.startsWith("/v1/") || isAdminPath(path));
    }

    private static boolean isAdminPath(String path) {
        return ADMIN_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            reject(response, 401, "missing bearer token");
            return;
        }
        String token = header.substring("Bearer ".length()).trim();

        if (masterKey != null && masterKey.equals(token)) {
            request.setAttribute(ATTR_IS_MASTER, true);
            chain.doFilter(request, response);
            return;
        }
        if (isAdminPath(request.getRequestURI())) {
            reject(response, 403, "admin endpoints require the master key");
            return;
        }

        Optional<VirtualKey> key = keys.find(token);
        if (key.isEmpty()) {
            reject(response, 401, "invalid api key");
            return;
        }
        VirtualKey vk = key.get();
        if (vk.blocked()) {
            reject(response, 401, "key is blocked");
            return;
        }
        if (vk.expired()) {
            reject(response, 401, "key has expired");
            return;
        }
        if (vk.overBudget()) {
            reject(response, 429, "key has exceeded its budget");
            return;
        }
        request.setAttribute(ATTR_VIRTUAL_KEY, vk);
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter()
                .write("{\"error\":{\"message\":\"" + message + "\",\"type\":\"auth_error\",\"code\":" + status + "}}");
    }
}
