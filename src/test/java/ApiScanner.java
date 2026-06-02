import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.*;

/**
 * Fully automatic Django/DRF API scanner.
 *
 * It discovers APIs directly from Django code:
 *  - router.register(...) ViewSet routes
 *  - path(...) / re_path(...)
 *  - include('app.urls') recursion
 *  - @api_view([...]) methods on function views
 *  - basic expected status inference from method and function body
 */
public class ApiScanner {

    private static final Pattern PATH_PATTERN = Pattern.compile(
            "(?:re_)?path\\s*\\(\\s*['\"]([^'\"]*)['\"]\\s*,\\s*([^,)]+)"
    );

    private static final Pattern INCLUDE_PATTERN = Pattern.compile(
            "include\\s*\\(\\s*['\"]([^'\"]+)['\"]"
    );

    private static final Pattern API_VIEW_PATTERN = Pattern.compile(
            "@api_view\\s*\\(\\s*\\[([^\\]]+)\\]"
    );

    private static final Pattern ROUTER_REGISTER_PATTERN = Pattern.compile(
            "router\\.register\\s*\\(\\s*r?['\"]([^'\"]+)['\"]\\s*,\\s*(?:views\\.)?(\\w+)"
    );

    private static final Pattern VIEWSET_CLASS_PATTERN = Pattern.compile(
            "class\\s+(\\w+)\\s*\\(([^)]*)\\)"
    );

    private static final Pattern HTTP_METHOD_NAMES_PATTERN = Pattern.compile(
            "http_method_names\\s*=\\s*\\[([^\\]]+)\\]"
    );

    private static final Pattern DJANGO_PARAM = Pattern.compile(
            "<(?:[^:>]+:)?(\\w+)>"
    );

    public static List<ApiConfig.ApiDefinition> scan(ApiConfig config) throws Exception {
        if (config.scanConfig == null) {
            throw new RuntimeException("scanConfig is missing in apis.json");
        }

        ApiConfig.ScanConfig sc = config.scanConfig;
        Path projectRoot = Paths.get(sc.projectPath).toAbsolutePath().normalize();

        if (!Files.exists(projectRoot)) {
            throw new RuntimeException(
                    "Django project path not found: " + projectRoot +
                            "\nUpdate scanConfig.projectPath in apis.json."
            );
        }

        System.out.println("🔍 Scanning Django project : " + projectRoot);

        Path entryUrls = findFile(projectRoot, sc.urlsFile != null ? sc.urlsFile : "urls.py");
        if (entryUrls == null) {
            throw new RuntimeException("Could not find urls file: " + sc.urlsFile + " under " + projectRoot);
        }
        System.out.println("📄 Entry URLs file         : " + entryUrls);

        Map<String, ViewSetInfo> viewSets = collectViewSets(projectRoot);
        System.out.println("🗂  ViewSets found          : " + viewSets.size());

        Map<String, ApiFunctionInfo> apiFunctions = collectApiFunctions(projectRoot);
        System.out.println("🔧 @api_view functions     : " + apiFunctions.size());

        List<RouteEntry> routes = new ArrayList<>();
        Set<Path> visited = new HashSet<>();
        parseUrlFile(entryUrls, "", projectRoot, routes, visited, viewSets, apiFunctions);

        System.out.println("✅ Total routes discovered : " + routes.size());
        System.out.println("──────────────────────────────────────────────────");

        return toApiDefinitions(routes, config);
    }

    private static void parseUrlFile(
            Path urlFile,
            String prefix,
            Path projectRoot,
            List<RouteEntry> routes,
            Set<Path> visited,
            Map<String, ViewSetInfo> viewSets,
            Map<String, ApiFunctionInfo> apiFunctions) throws Exception {

        urlFile = urlFile.toAbsolutePath().normalize();
        if (visited.contains(urlFile) || !Files.exists(urlFile)) return;
        visited.add(urlFile);

        String content = Files.readString(urlFile);
        String[] lines = content.split("\n");

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isBlank() || line.startsWith("#")) continue;

            Matcher routerMatcher = ROUTER_REGISTER_PATTERN.matcher(line);
            if (routerMatcher.find()) {
                String routerPrefix = routerMatcher.group(1);
                String viewSetName = routerMatcher.group(2).trim();
                String fullPrefix = joinPaths(prefix, routerPrefix);

                ViewSetInfo vs = viewSets.get(viewSetName);
                List<String> methods = vs != null ? vs.getMethods() : List.of("GET", "POST", "PUT", "PATCH", "DELETE");

                for (String method : listMethods(methods)) {
                    routes.add(new RouteEntry(fullPrefix + "/", method, viewSetName + " (list)", expectedForViewSet(method)));
                }
                for (String method : detailMethods(methods)) {
                    routes.add(new RouteEntry(fullPrefix + "/{pk}/", method, viewSetName + " (detail)", expectedForViewSet(method)));
                }
                continue;
            }

            Matcher pathMatcher = PATH_PATTERN.matcher(line);
            if (!pathMatcher.find()) continue;

            String urlPattern = pathMatcher.group(1);
            String viewPart = pathMatcher.group(2).trim();
            String fullPath = joinPaths(prefix, urlPattern);

            Matcher includeMatcher = INCLUDE_PATTERN.matcher(line);
            if (includeMatcher.find() || line.contains("include(")) {
                Matcher inc = INCLUDE_PATTERN.matcher(line);
                if (inc.find()) {
                    Path childFile = resolveModule(inc.group(1), projectRoot);
                    if (childFile != null) {
                        parseUrlFile(childFile, fullPath, projectRoot, routes, visited, viewSets, apiFunctions);
                    }
                }
                continue;
            }

            if (viewPart.contains("as_view")) {
                String className = cleanViewName(viewPart.replaceAll("\\.as_view.*", ""));
                ViewSetInfo vs = viewSets.get(className);
                List<String> methods = extractAsViewMethods(viewPart);
                if (methods.isEmpty()) methods = vs != null ? vs.getMethods() : List.of("GET");

                for (String method : methods) {
                    routes.add(new RouteEntry(fullPath, method, className, expectedForMethod(method)));
                }
                continue;
            }

            String funcName = cleanViewName(viewPart.replaceAll("[,(].*", ""));
            ApiFunctionInfo fn = apiFunctions.get(funcName);

            if (fn != null) {
                for (String method : fn.methods) {
                    routes.add(new RouteEntry(fullPath, method, funcName, fn.expectedStatus(method)));
                }
            } else {
                routes.add(new RouteEntry(fullPath, "GET", funcName, 200));
            }
        }
    }

    private static Map<String, ViewSetInfo> collectViewSets(Path projectRoot) throws Exception {
        Map<String, ViewSetInfo> result = new HashMap<>();

        Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return shouldSkip(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!file.toString().endsWith(".py")) return FileVisitResult.CONTINUE;

                String content = Files.readString(file);
                String[] lines = content.split("\n");

                for (int i = 0; i < lines.length; i++) {
                    Matcher m = VIEWSET_CLASS_PATTERN.matcher(lines[i]);
                    if (!m.find()) continue;

                    String className = m.group(1);
                    String parents = m.group(2);
                    ViewSetInfo info = new ViewSetInfo(className, parents);

                    for (int j = i + 1; j < Math.min(i + 80, lines.length); j++) {
                        String next = lines[j].trim();
                        if (next.startsWith("class ")) break;

                        Matcher mm = HTTP_METHOD_NAMES_PATTERN.matcher(next);
                        if (mm.find()) {
                            info.explicitMethods = parseMethodList(mm.group(1));
                            break;
                        }
                    }
                    result.put(className, info);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }

    private static Map<String, ApiFunctionInfo> collectApiFunctions(Path projectRoot) throws Exception {
        Map<String, ApiFunctionInfo> result = new HashMap<>();

        Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return shouldSkip(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!file.toString().endsWith(".py")) return FileVisitResult.CONTINUE;

                String content = Files.readString(file);
                String[] lines = content.split("\n");

                for (int i = 0; i < lines.length; i++) {
                    Matcher apiView = API_VIEW_PATTERN.matcher(lines[i]);
                    if (!apiView.find()) continue;

                    List<String> methods = parseMethodList(apiView.group(1));
                    int defLine = -1;
                    String funcName = null;

                    for (int j = i + 1; j < Math.min(i + 8, lines.length); j++) {
                        String next = lines[j].trim();
                        if (next.startsWith("def ")) {
                            funcName = next.replaceAll("def\\s+(\\w+).*", "$1");
                            defLine = j;
                            break;
                        }
                    }

                    if (funcName == null) continue;

                    StringBuilder body = new StringBuilder();
                    for (int j = defLine + 1; j < lines.length; j++) {
                        String next = lines[j];
                        String trimmed = next.trim();
                        if (trimmed.startsWith("@api_view") || trimmed.startsWith("def ") || trimmed.startsWith("class ")) break;
                        body.append(next).append('\n');
                    }

                    ApiFunctionInfo info = new ApiFunctionInfo(funcName, methods);
                    info.detectedStatus = detectStatusFromBody(body.toString());
                    result.put(funcName, info);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }

    private static int detectStatusFromBody(String body) {
        if (body.contains("HTTP_201_CREATED")) return 201;
        if (body.contains("HTTP_204_NO_CONTENT")) return 204;
        if (body.contains("HTTP_400_BAD_REQUEST")) return 400;
        if (body.contains("HTTP_401_UNAUTHORIZED")) return 401;
        if (body.contains("HTTP_403_FORBIDDEN")) return 403;
        if (body.contains("HTTP_404_NOT_FOUND")) return 404;

        Matcher numeric = Pattern.compile("status\\s*=\\s*(\\d{3})").matcher(body);
        if (numeric.find()) return Integer.parseInt(numeric.group(1));

        return -1;
    }

    private static Path findFile(Path root, String name) throws IOException {
        if (name != null && (name.contains("/") || name.contains("\\\\"))) {
            Path candidate = root.resolve(name.replace("/", File.separator)).toAbsolutePath().normalize();
            if (Files.exists(candidate)) return candidate;
        }

        final Path[] found = {null};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return shouldSkip(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

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

    private static Path resolveModule(String module, Path root) {
        String relative = module.replace(".", File.separator) + ".py";
        Path candidate = root.resolve(relative).toAbsolutePath().normalize();
        if (Files.exists(candidate)) return candidate;

        String[] parts = module.split("\\.");
        if (parts.length > 1) {
            String sub = String.join(File.separator, Arrays.copyOfRange(parts, 1, parts.length)) + ".py";
            Path candidate2 = root.resolve(sub).toAbsolutePath().normalize();
            if (Files.exists(candidate2)) return candidate2;
        }
        return null;
    }

    private static boolean shouldSkip(Path dir) {
        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
        return name.equals("venv") || name.equals(".venv") || name.equals("env") ||
                name.equals("__pycache__") || name.equals(".git") || name.equals("node_modules") ||
                name.equals("target") || name.equals(".idea");
    }

    private static String cleanViewName(String viewPart) {
        String cleaned = viewPart.trim();
        if (cleaned.contains(".")) cleaned = cleaned.substring(cleaned.lastIndexOf('.') + 1);
        cleaned = cleaned.replaceAll("[^A-Za-z0-9_]", "");
        return cleaned;
    }

    private static String joinPaths(String prefix, String suffix) {
        String p = prefix == null ? "" : prefix.trim();
        String s = suffix == null ? "" : suffix.trim();

        if (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (s.startsWith("/")) s = s.substring(1);

        String joined = p.isEmpty() ? "/" + s : p + "/" + s;
        if (!joined.startsWith("/")) joined = "/" + joined;
        return joined.replaceAll("//+", "/");
    }

    private static String normalizePath(String path, Map<String, String> paramDefaults) {
        String normalized = DJANGO_PARAM.matcher(path).replaceAll("{$1}");
        normalized = normalized.replaceAll("\\(\\?P<(\\w+)>[^)]+\\)", "{$1}");
        normalized = normalized.replaceAll("\\$", "");

        for (Map.Entry<String, String> entry : paramDefaults.entrySet()) {
            normalized = normalized.replace(entry.getKey(), entry.getValue());
        }
        return normalized.replaceAll("//+", "/");
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

    private static int expectedForMethod(String method) {
        return switch (method) {
            case "POST" -> 201;
            case "DELETE" -> 204;
            default -> 200;
        };
    }

    private static int expectedForViewSet(String method) {
        return expectedForMethod(method);
    }

    private static List<ApiConfig.ApiDefinition> toApiDefinitions(List<RouteEntry> routes, ApiConfig config) {
        ApiConfig.ScanConfig sc = config.scanConfig;
        Map<String, Integer> statusMap = sc.defaultExpectedStatus != null
                ? sc.defaultExpectedStatus
                : Map.of("GET", 200, "POST", 201, "PUT", 200, "PATCH", 200, "DELETE", 204);
        Map<String, String> paramDefaults = sc.pathParamDefaults != null
                ? sc.pathParamDefaults
                : Map.of("{id}", "1", "{pk}", "1", "{slug}", "test-slug", "{uuid}", "00000000-0000-0000-0000-000000000001");

        Set<String> seen = new LinkedHashSet<>();
        List<ApiConfig.ApiDefinition> defs = new ArrayList<>();

        for (RouteEntry r : routes) {
            String endpoint = normalizePath(r.path, paramDefaults);
            String method = r.method.toUpperCase();
            String key = method + " " + endpoint;
            if (!seen.add(key)) continue;

            ApiConfig.ApiDefinition def = new ApiConfig.ApiDefinition();
            def.name = method + " " + endpoint + " (" + r.viewName + ")";
            def.method = method;
            def.endpoint = endpoint;
            def.headers = Map.of("Content-Type", "application/json", "Accept", "application/json");
            def.body = dummyBodyFor(method, endpoint);
            def.expectedStatusCode = r.expectedStatus > 0 ? r.expectedStatus : statusMap.getOrDefault(method, expectedForMethod(method));
            defs.add(def);
        }
        return defs;
    }

    private static Map<String, Object> dummyBodyFor(String method, String endpoint) {
        if (!(method.equals("POST") || method.equals("PUT") || method.equals("PATCH"))) return null;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Auto Test");
        body.put("email", "auto.test@example.com");
        body.put("mobile", "9123456789");
        body.put("phone_number", "+919123456789");
        body.put("status", "test");

        if (endpoint.contains("otp/create")) {
            body.clear();
            body.put("phone_number", "+919123456789");
            body.put("send_whatsapp", true);
        } else if (endpoint.contains("otp/validate")) {
            body.clear();
            body.put("phone_number", "+919123456789");
            body.put("otp", "123456");
        } else if (endpoint.contains("user-login")) {
            body.clear();
            body.put("email", "test@example.com");
            body.put("password", "123456");
        }
        return body;
    }

    private static class RouteEntry {
        final String path;
        final String method;
        final String viewName;
        final int expectedStatus;

        RouteEntry(String path, String method, String viewName, int expectedStatus) {
            this.path = path;
            this.method = method;
            this.viewName = viewName;
            this.expectedStatus = expectedStatus;
        }
    }

    private static class ApiFunctionInfo {
        final String name;
        final List<String> methods;
        int detectedStatus = -1;

        ApiFunctionInfo(String name, List<String> methods) {
            this.name = name;
            this.methods = methods;
        }

        int expectedStatus(String method) {
            if (detectedStatus > 0) return detectedStatus;
            return method.equals("DELETE") ? 204 : 200;
        }
    }

    private static class ViewSetInfo {
        final String className;
        final String parents;
        final boolean isViewSet;
        List<String> explicitMethods;

        ViewSetInfo(String className, String parents) {
            this.className = className;
            this.parents = parents;
            this.isViewSet = parents.contains("ViewSet") || parents.contains("ModelViewSet") ||
                    parents.contains("GenericViewSet") || parents.contains("ReadOnlyModelViewSet");
        }

        List<String> getMethods() {
            if (explicitMethods != null) return explicitMethods;
            if (parents.contains("ReadOnlyModelViewSet")) return List.of("GET");
            if (parents.contains("ModelViewSet")) return List.of("GET", "POST", "PUT", "PATCH", "DELETE");
            if (parents.contains("CreateModelMixin")) return List.of("POST");
            if (parents.contains("UpdateModelMixin")) return List.of("PUT", "PATCH");
            if (parents.contains("DestroyModelMixin")) return List.of("DELETE");
            if (parents.contains("ListModelMixin") || parents.contains("RetrieveModelMixin")) return List.of("GET");
            return List.of("GET", "POST", "PUT", "PATCH", "DELETE");
        }
    }
}
