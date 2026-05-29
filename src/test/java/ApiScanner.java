import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.*;

/**
 * Scans a Django REST Framework project and auto-discovers all API endpoints.
 *
 * Supports:
 *  - urlpatterns path() and re_path() in urls.py files
 *  - include() to follow nested URL files
 *  - @api_view(['GET','POST',...]) on function-based views
 *  - ViewSet router registrations (router.register)
 *  - Class-based views with http_method_names or standard CRUD naming
 *  - Django REST Framework ModelViewSet / ViewSet method inference
 */
public class ApiScanner {

    // ── Regex patterns ────────────────────────────────────────────────────────

    // path('users/', UserView.as_view(), name='users')
    private static final Pattern PATH_PATTERN = Pattern.compile(
        "(?:re_)?path\\s*\\(\\s*['\"]([^'\"]*)['\"]\\s*,\\s*([^,)]+)"
    );

    // include('app.urls') or include('app.urls', namespace='api')
    private static final Pattern INCLUDE_PATTERN = Pattern.compile(
        "include\\s*\\(\\s*['\"]([^'\"]+)['\"]"
    );

    // @api_view(['GET', 'POST'])
    private static final Pattern API_VIEW_PATTERN = Pattern.compile(
        "@api_view\\s*\\(\\s*\\[([^\\]]+)\\]"
    );

    // router.register(r'users', UserViewSet, basename='user')
    private static final Pattern ROUTER_REGISTER_PATTERN = Pattern.compile(
        "router\\.register\\s*\\(\\s*r?['\"]([^'\"]+)['\"]\\s*,\\s*(\\w+)"
    );

    // class UserViewSet(ModelViewSet): or (ViewSet): etc.
    private static final Pattern VIEWSET_CLASS_PATTERN = Pattern.compile(
        "class\\s+(\\w+)\\s*\\(([^)]+)\\)"
    );

    // http_method_names = ['get', 'post']
    private static final Pattern HTTP_METHOD_NAMES_PATTERN = Pattern.compile(
        "http_method_names\\s*=\\s*\\[([^\\]]+)\\]"
    );

    // Django path parameter: <int:pk>, <str:slug>, <uuid:id>
    private static final Pattern DJANGO_PARAM = Pattern.compile(
        "<(?:[^:>]+:)?(\\w+)>"
    );

    // ── Entry point ───────────────────────────────────────────────────────────

    /**
     * Scans the Django project and returns a list of discovered ApiDefinitions.
     *
     * @param config  full ApiConfig (reads scanConfig fields)
     */
    public static List<ApiConfig.ApiDefinition> scan(ApiConfig config) throws Exception {
        ApiConfig.ScanConfig sc = config.scanConfig;

        // Resolve project root
        Path projectRoot = Paths.get(sc.projectPath).toAbsolutePath().normalize();
        if (!Files.exists(projectRoot)) {
            throw new RuntimeException(
                "Django project path not found: " + projectRoot +
                "\nUpdate 'scanConfig.projectPath' in apis.json."
            );
        }

        System.out.println("🔍 Scanning Django project : " + projectRoot);

        // Find the entry-point urls.py
        Path entryUrls = findFile(projectRoot, sc.urlsFile != null ? sc.urlsFile : "urls.py");
        if (entryUrls == null) {
            throw new RuntimeException(
                "Could not find '" + sc.urlsFile + "' under " + projectRoot
            );
        }
        System.out.println("📄 Entry URLs file         : " + entryUrls);

        // Collect ViewSet class info from all .py files first
        Map<String, ViewSetInfo> viewSets = collectViewSets(projectRoot);
        System.out.println("🗂  ViewSets found          : " + viewSets.size());

        // Collect @api_view decorators from all .py files
        Map<String, List<String>> apiFunctions = collectApiFunctions(projectRoot);
        System.out.println("🔧 @api_view functions     : " + apiFunctions.size());

        // Walk URL files recursively
        List<RouteEntry> routes = new ArrayList<>();
        Set<Path> visited = new HashSet<>();
        parseUrlFile(entryUrls, "", projectRoot, routes, visited, viewSets, apiFunctions);

        // Also scan all urls.py files not reachable via include() (common in Django apps)
        List<Path> allUrlFiles = findAllUrlFiles(projectRoot);
        for (Path urlFile : allUrlFiles) {
            if (!visited.contains(urlFile)) {
                System.out.println("   ↳ Also scanning orphan: " + projectRoot.relativize(urlFile));
                parseUrlFile(urlFile, "", projectRoot, routes, visited, viewSets, apiFunctions);
            }
        }

        System.out.println("✅ Total routes discovered : " + routes.size());
        System.out.println("──────────────────────────────────────────────────");

        // Convert routes → ApiDefinitions
        return toApiDefinitions(routes, config);
    }

    // ── URL file parser ───────────────────────────────────────────────────────

    private static void parseUrlFile(
            Path urlFile,
            String prefix,
            Path projectRoot,
            List<RouteEntry> routes,
            Set<Path> visited,
            Map<String, ViewSetInfo> viewSets,
            Map<String, List<String>> apiFunctions) throws Exception {

        if (visited.contains(urlFile)) return;
        visited.add(urlFile);

        if (!Files.exists(urlFile)) return;

        String content = Files.readString(urlFile);
        String[] lines = content.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            // Skip comments
            if (line.startsWith("#")) continue;

            // ── router.register ───────────────────────────────────────────────
            Matcher routerMatcher = ROUTER_REGISTER_PATTERN.matcher(line);
            if (routerMatcher.find()) {
                String routerPrefix = routerMatcher.group(1);
                String viewSetName  = routerMatcher.group(2).trim();
                String fullPrefix   = joinPaths(prefix, routerPrefix);

                ViewSetInfo vs = viewSets.get(viewSetName);
                List<String> methods = vs != null ? vs.getMethods() : List.of("GET","POST","PUT","PATCH","DELETE");

                // List route: /prefix/
                for (String method : listMethods(methods)) {
                    routes.add(new RouteEntry(fullPrefix + "/", method, viewSetName + " (list)"));
                }
                // Detail route: /prefix/{pk}/
                for (String method : detailMethods(methods)) {
                    routes.add(new RouteEntry(fullPrefix + "/{pk}/", method, viewSetName + " (detail)"));
                }
                continue;
            }

            // ── path() / re_path() ────────────────────────────────────────────
            Matcher pathMatcher = PATH_PATTERN.matcher(line);
            if (pathMatcher.find()) {
                String urlPattern = pathMatcher.group(1);
                String viewPart   = pathMatcher.group(2).trim();
                String fullPath   = joinPaths(prefix, urlPattern);

                // include() → recurse
                Matcher includeMatcher = INCLUDE_PATTERN.matcher(line);
                if (includeMatcher.find() || line.contains("include(")) {
                    // Try to extract module path from include
                    Matcher inc2 = INCLUDE_PATTERN.matcher(line);
                    if (inc2.find()) {
                        String modulePath = inc2.group(1); // e.g. "users.urls"
                        Path childFile = resolveModule(modulePath, projectRoot);
                        if (childFile != null) {
                            parseUrlFile(childFile, fullPath, projectRoot, routes, visited, viewSets, apiFunctions);
                        }
                    }
                    continue;
                }

                // as_view() → check if ViewSet or regular view
                if (viewPart.contains("as_view()") || viewPart.contains("as_view({")) {
                    String className = viewPart.replaceAll("\\.as_view.*", "").trim();
                    ViewSetInfo vs = viewSets.get(className);

                    if (vs != null && vs.isViewSet) {
                        // ViewSet used directly via as_view({'get': 'list'}) etc.
                        List<String> methods = extractAsViewMethods(viewPart);
                        if (methods.isEmpty()) methods = vs.getMethods();
                        for (String method : methods) {
                            routes.add(new RouteEntry(fullPath, method, className));
                        }
                    } else if (vs != null) {
                        for (String method : vs.getMethods()) {
                            routes.add(new RouteEntry(fullPath, method, className));
                        }
                    } else {
                        // Unknown class — default GET
                        routes.add(new RouteEntry(fullPath, "GET", className));
                    }
                    continue;
                }

                // Function-based view
                String funcName = viewPart.replaceAll("[,(].*", "").trim();
                List<String> apiViewMethods = apiFunctions.get(funcName);
                if (apiViewMethods != null) {
                    for (String method : apiViewMethods) {
                        routes.add(new RouteEntry(fullPath, method, funcName));
                    }
                } else {
                    // Plain view function — assume GET
                    routes.add(new RouteEntry(fullPath, "GET", funcName));
                }
            }
        }
    }

    // ── ViewSet collector ─────────────────────────────────────────────────────

    private static Map<String, ViewSetInfo> collectViewSets(Path projectRoot) throws Exception {
        Map<String, ViewSetInfo> result = new HashMap<>();

        Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!file.toString().endsWith(".py")) return FileVisitResult.CONTINUE;
                try {
                    String content = Files.readString(file);
                    String[] lines = content.split("\n");
                    for (int i = 0; i < lines.length; i++) {
                        Matcher m = VIEWSET_CLASS_PATTERN.matcher(lines[i]);
                        if (m.find()) {
                            String className = m.group(1);
                            String parents   = m.group(2);
                            ViewSetInfo info = new ViewSetInfo(className, parents);

                            // Look ahead for http_method_names
                            for (int j = i + 1; j < Math.min(i + 30, lines.length); j++) {
                                Matcher mm = HTTP_METHOD_NAMES_PATTERN.matcher(lines[j]);
                                if (mm.find()) {
                                    info.explicitMethods = parseMethodList(mm.group(1));
                                    break;
                                }
                                // Stop at next class definition
                                if (lines[j].trim().startsWith("class ")) break;
                            }
                            result.put(className, info);
                        }
                    }
                } catch (IOException ignored) {}
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }

    // ── @api_view collector ───────────────────────────────────────────────────

    private static Map<String, List<String>> collectApiFunctions(Path projectRoot) throws Exception {
        Map<String, List<String>> result = new HashMap<>();

        Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!file.toString().endsWith(".py")) return FileVisitResult.CONTINUE;
                try {
                    String content = Files.readString(file);
                    String[] lines = content.split("\n");
                    for (int i = 0; i < lines.length - 1; i++) {
                        Matcher m = API_VIEW_PATTERN.matcher(lines[i]);
                        if (m.find()) {
                            List<String> methods = parseMethodList(m.group(1));
                            // Next non-empty line should be: def function_name(...)
                            for (int j = i + 1; j < Math.min(i + 5, lines.length); j++) {
                                String next = lines[j].trim();
                                if (next.startsWith("def ")) {
                                    String funcName = next.replaceAll("def (\\w+).*", "$1");
                                    result.put(funcName, methods);
                                    break;
                                }
                            }
                        }
                    }
                } catch (IOException ignored) {}
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Find a file by name anywhere under root */
    private static Path findFile(Path root, String name) throws IOException {
        final Path[] found = {null};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName().toString().equals(name)) {
                    found[0] = file;
                    return FileVisitResult.TERMINATE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return found[0];
    }

    /** Find ALL urls.py files under root */
    private static List<Path> findAllUrlFiles(Path root) throws IOException {
        List<Path> result = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName().toString().equals("urls.py")) {
                    result.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName().toString();
                // Skip virtualenv / cache directories
                if (name.equals("venv") || name.equals(".venv") || name.equals("env") ||
                    name.equals("__pycache__") || name.equals(".git") || name.equals("node_modules")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }

    /** Convert Python module path like "users.urls" → actual file path */
    private static Path resolveModule(String module, Path root) {
        String relative = module.replace(".", File.separator) + ".py";
        Path candidate = root.resolve(relative);
        if (Files.exists(candidate)) return candidate;

        // Try without leading app name
        String[] parts = module.split("\\.");
        if (parts.length > 1) {
            String sub = String.join(File.separator, Arrays.copyOfRange(parts, 1, parts.length)) + ".py";
            Path candidate2 = root.resolve(sub);
            if (Files.exists(candidate2)) return candidate2;
        }
        return null;
    }

    /** Join URL path segments cleanly */
    private static String joinPaths(String prefix, String suffix) {
        String p = prefix.endsWith("/")   ? prefix : prefix + "/";
        String s = suffix.startsWith("/") ? suffix.substring(1) : suffix;
        String joined = (p + s);
        if (!joined.startsWith("/")) joined = "/" + joined;
        return joined;
    }

    /** Convert Django <int:pk> style params to {pk} style */
    private static String normalizePath(String path, Map<String, String> paramDefaults) {
        // Replace <type:name> and <name> with {name}
        String normalized = DJANGO_PARAM.matcher(path).replaceAll("{$1}");
        // Replace regex groups like (?P<pk>[0-9]+) with {pk}
        normalized = normalized.replaceAll("\\(\\?P<(\\w+)>[^)]+\\)", "{$1}");
        // Clean trailing regex anchors
        normalized = normalized.replaceAll("\\$", "");
        // Substitute actual values for path params so tests run
        for (Map.Entry<String, String> entry : paramDefaults.entrySet()) {
            normalized = normalized.replace(entry.getKey(), entry.getValue());
        }
        return normalized;
    }

    private static List<String> parseMethodList(String raw) {
        List<String> methods = new ArrayList<>();
        Matcher m = Pattern.compile("['\"]([A-Z]+)['\"]").matcher(raw.toUpperCase());
        while (m.find()) methods.add(m.group(1));
        return methods.isEmpty() ? List.of("GET") : methods;
    }

    private static List<String> extractAsViewMethods(String asViewCall) {
        List<String> methods = new ArrayList<>();
        Matcher m = Pattern.compile("['\"]([a-zA-Z]+)['\"]\\s*:").matcher(asViewCall);
        while (m.find()) methods.add(m.group(1).toUpperCase());
        return methods;
    }

    private static List<String> listMethods(List<String> all) {
        List<String> list = new ArrayList<>();
        for (String m : all) if (m.equals("GET") || m.equals("POST")) list.add(m);
        return list.isEmpty() ? List.of("GET") : list;
    }

    private static List<String> detailMethods(List<String> all) {
        List<String> list = new ArrayList<>();
        for (String m : all) if (!m.equals("POST")) list.add(m);
        return list.isEmpty() ? List.of("GET") : list;
    }

    /** Convert raw RouteEntry list → ApiConfig.ApiDefinition list */
    private static List<ApiConfig.ApiDefinition> toApiDefinitions(
            List<RouteEntry> routes, ApiConfig config) {

        ApiConfig.ScanConfig sc = config.scanConfig;
        Map<String, Integer> statusMap   = sc.defaultExpectedStatus != null
                ? sc.defaultExpectedStatus
                : Map.of("GET",200,"POST",201,"PUT",200,"PATCH",200,"DELETE",204);
        Map<String, String> paramDefaults = sc.pathParamDefaults != null
                ? sc.pathParamDefaults
                : Map.of("{id}","1","{pk}","1","{slug}","test-slug");

        // Deduplicate by (endpoint, method)
        Set<String> seen = new LinkedHashSet<>();
        List<ApiConfig.ApiDefinition> defs = new ArrayList<>();

        for (RouteEntry r : routes) {
            String endpoint = normalizePath(r.path, paramDefaults);
            String key = r.method + " " + endpoint;
            if (!seen.add(key)) continue;

            ApiConfig.ApiDefinition def = new ApiConfig.ApiDefinition();
            def.name     = r.method + " " + endpoint + " (" + r.viewName + ")";
            def.method   = r.method;
            def.endpoint = endpoint;
            def.headers  = Map.of("Content-Type", "application/json");
            def.body     = null;
            def.expectedStatusCode = statusMap.getOrDefault(r.method, 200);
            defs.add(def);
        }
        return defs;
    }

    // ── Inner classes ─────────────────────────────────────────────────────────

    private static class RouteEntry {
        final String path;
        final String method;
        final String viewName;
        RouteEntry(String path, String method, String viewName) {
            this.path     = path;
            this.method   = method;
            this.viewName = viewName;
        }
    }

    private static class ViewSetInfo {
        final String       className;
        final String       parents;
        final boolean      isViewSet;
        List<String>       explicitMethods = null;

        ViewSetInfo(String className, String parents) {
            this.className = className;
            this.parents   = parents;
            this.isViewSet = parents.contains("ViewSet") || parents.contains("ModelViewSet")
                          || parents.contains("GenericViewSet") || parents.contains("ReadOnlyModelViewSet");
        }

        List<String> getMethods() {
            if (explicitMethods != null) return explicitMethods;
            // Infer from parent class
            if (parents.contains("ReadOnlyModelViewSet")) return List.of("GET");
            if (parents.contains("ModelViewSet"))         return List.of("GET","POST","PUT","PATCH","DELETE");
            if (parents.contains("CreateModelMixin"))     return List.of("POST");
            if (parents.contains("UpdateModelMixin"))     return List.of("PUT","PATCH");
            if (parents.contains("DestroyModelMixin"))    return List.of("DELETE");
            if (parents.contains("ListModelMixin") || parents.contains("RetrieveModelMixin"))
                                                          return List.of("GET");
            if (parents.contains("APIView"))              return List.of("GET");
            return List.of("GET","POST","PUT","PATCH","DELETE");
        }
    }
}
