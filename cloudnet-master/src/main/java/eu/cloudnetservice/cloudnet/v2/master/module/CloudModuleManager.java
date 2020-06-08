package eu.cloudnetservice.cloudnet.v2.master.module;

import com.google.common.collect.Maps;
import com.google.common.graph.Graph;
import com.google.common.graph.GraphBuilder;
import com.google.common.graph.MutableGraph;
import eu.cloudnetservice.cloudnet.v2.master.module.exception.ModuleDescriptionFileNotFoundException;
import eu.cloudnetservice.cloudnet.v2.master.module.exception.ModuleNotFoundException;
import eu.cloudnetservice.cloudnet.v2.master.module.model.CloudModuleDependency;
import eu.cloudnetservice.cloudnet.v2.master.module.model.CloudModuleDescriptionFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

public final class CloudModuleManager {

    private final Map<String, CloudModule> modules;
    private final Collection<Path> toLoad = new CopyOnWriteArrayList<>();
    private final Path moduleDirectory;

    public CloudModuleManager() {
        modules = new LinkedHashMap<>();
        this.moduleDirectory = Paths.get("modules");
        if (!Files.exists(this.moduleDirectory)) {
            try {
                Files.createDirectory(this.moduleDirectory);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void detectModules() {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(this.moduleDirectory, "*.jar")) {
            for (Path path : stream) {
                if (this.isModuleDetectedByPath(path)) {
                    continue;
                }
                toLoad.add(path);
            }
        } catch (final IOException ex) {
            ex.printStackTrace();
        }
        handleLoaded();
    }

    public void disableModule(CloudModule module) {
        if (module instanceof JavaCloudModule) {
            if (module.isEnabled()) {
                String name = String.format("%s:%s:%s",
                                            module.getModuleJson().getGroupId(),
                                            module.getModuleJson().getName(),
                                            module.getModuleJson().getVersion());
                module.getModuleLogger().info(String.format("Disabling %s from %s", name, module.getModuleJson().getAuthorsAsString()));
                JavaCloudModule javaCloudModule = (JavaCloudModule) module;
                if (this.modules.containsKey(name)) {
                    javaCloudModule.setEnabled(false);
                }
            }
        } else {
            System.err.println("Module is not associated with this ModuleLoader");
        }
    }

    public void enableModule(CloudModule module) {

        if (module instanceof JavaCloudModule) {
            JavaCloudModule javaCloudModule = (JavaCloudModule) module;
            final List<CloudModule> cloudModuleDescriptionFiles = this.resolveDependenciesSortedSingle(new ArrayList<>(getModules().values()),
                                                                                                           javaCloudModule);
            final Set<CloudModule> loadOrder = new HashSet<>();
            load:
            for (CloudModule descriptionFile : cloudModuleDescriptionFiles) {
                String moduleName = descriptionFile.getModuleJson().getGroupId() + ":" + descriptionFile.getModuleJson().getName();
                for (final CloudModuleDependency dependency : descriptionFile.getModuleJson().getDependencies()) {
                    String dependName = dependency.getGroupId() + ":" + dependency.getName();
                    final Optional<CloudModule> omodule = getModule(dependName);
                    if (!omodule.isPresent()) {
                        System.err.println("unable to load module " + moduleName + " because of missing dependency " + dependName);
                        this.modules.remove(moduleName);
                        continue load;
                    }
                    if (!omodule.get().getModuleJson().getSemVersion().satisfies(dependency.getVersion())) {
                        System.err.println("Cannot load module " + moduleName + " because of missing dependency with version " + dependency
                            .getVersion());
                        this.modules.remove(moduleName);
                        continue load;
                    }
                    omodule.ifPresent(loadOrder::add);
                }
                loadOrder.add(descriptionFile);
            }

            List<CloudModule> forLoading = new ArrayList<>(resolveDependenciesSorted(new ArrayList<>(loadOrder)));
            Collections.reverse(forLoading);
            forLoading.forEach(cloudModule -> {
                if (!cloudModule.isEnabled()) {
                    cloudModule.getModuleLogger().info(String.format("Enabling module %s from %s",
                                                                     cloudModule.getModuleJson().getName(),
                                                                     cloudModule.getModuleJson().getAuthorsAsString()));
                    cloudModule.setEnabled(true);
                }
            });
        } else {
            System.err.println("Module is not associated with this ModuleLoader");
        }
    }

    private void handleLoaded() {
        for (Path path : this.toLoad) {
            Optional<JavaCloudModule> cloudModule = loadModule(path);
            cloudModule.ifPresent(javaCloudModule -> this.modules.put(javaCloudModule.getModuleJson()
                                                                                     .getGroupId() + ":" + javaCloudModule.getModuleJson()
                                                                                                                          .getName(),
                                                                      javaCloudModule));
            this.toLoad.remove(path);
        }
        final List<CloudModule> cloudModuleDescriptionFiles = resolveDependenciesSorted(new ArrayList<>(getModules().values()));
        final Set<CloudModule> loadOrder = new HashSet<>();
        load:
        for (CloudModule descriptionFile : cloudModuleDescriptionFiles) {
            String moduleName = descriptionFile.getModuleJson().getGroupId() + ":" + descriptionFile.getModuleJson().getName();
            for (final CloudModuleDependency dependency : descriptionFile.getModuleJson().getDependencies()) {
                String name = dependency.getGroupId() + ":" + dependency.getName();
                final Optional<CloudModule> omodule = getModule(name);
                if (!omodule.isPresent()) {
                    System.err.println("unable to load module " + moduleName + " because of missing dependency " + name);
                    this.modules.remove(moduleName);
                    continue load;
                }
                if (!omodule.get().getModuleJson().getSemVersion().satisfies(dependency.getVersion())) {
                    System.err.println("Cannot load module " + moduleName + " because of missing dependency with version " + dependency.getVersion());
                    this.modules.remove(moduleName);
                    continue load;
                }
                omodule.ifPresent(loadOrder::add);
            }
            loadOrder.add(descriptionFile);
        }
        List<CloudModule> forLoading = new ArrayList<>(resolveDependenciesSorted(new ArrayList<>(loadOrder)));
        Collections.reverse(forLoading);
        forLoading.forEach(javaCloudModule -> {
            if (!javaCloudModule.isLoaded()) {
                javaCloudModule.getModuleLogger().info(String.format("Loading module %s from %s",
                                                                     javaCloudModule.getModuleJson().getName(),
                                                                     javaCloudModule.getModuleJson().getAuthorsAsString()));
                javaCloudModule.setLoaded(true);
            }
        });
    }

    public Optional<JavaCloudModule> loadModule(Path path) {
        Optional<JavaCloudModule> javaModule = Optional.empty();
        try {
            Optional<CloudModuleDescriptionFile> cloudModuleDescriptionFile = getCloudModuleDescriptionFile(path);
            if (cloudModuleDescriptionFile.isPresent()) {
                ModuleClassLoader classLoader = new ModuleClassLoader(getClass().getClassLoader(), path);
                final Class<?> jarClazz = classLoader.loadClass(cloudModuleDescriptionFile.get().getMain());
                final Class<? extends JavaCloudModule> mainClazz = jarClazz.asSubclass(JavaCloudModule.class);
                final JavaCloudModule javaCloudModule = mainClazz.getDeclaredConstructor().newInstance();
                javaModule = Optional.of(javaCloudModule);
                javaModule.ifPresent(cloudModule -> cloudModule.init(classLoader,cloudModuleDescriptionFile.get()));
            }
        } catch (MalformedURLException | ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InstantiationException | InvocationTargetException e) {
            e.printStackTrace();
        }
        return javaModule;
    }

    public Optional<CloudModuleDescriptionFile> getCloudModuleDescriptionFile(Path module) {
        if (module != null) {
            try (JarFile moduleJar = new JarFile(module.toFile())) {
                ZipEntry moduleJsonFile = moduleJar.getEntry("module_v2.json");
                if (moduleJsonFile == null) {
                    throw new ModuleDescriptionFileNotFoundException("The module don't contain a module_v2.json!");
                }
                try (InputStream stream = moduleJar.getInputStream(moduleJsonFile)) {
                    CloudModuleDescriptionFile moduleDescriptionFile = new CloudModuleDescriptionFile(stream, module);
                    if (moduleDescriptionFile.getSemVersion().getMajor() != null && moduleDescriptionFile.getSemVersion()
                                                                                                         .getMajor() != null && moduleDescriptionFile
                        .getSemVersion()
                        .getPatch() != null) {
                        return Optional.of(moduleDescriptionFile);
                    } else {
                        System.err.println(String.format("Module(%s) not enabling, wrong version format %s",
                                                         moduleDescriptionFile.getName(),
                                                         moduleDescriptionFile.getVersion()));
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("Something is wrong with the jar", e);
            }
        } else {
            throw new ModuleNotFoundException("Module file not found");
        }
        return Optional.empty();
    }

    private boolean isModuleDetectedByPath(@NotNull Path path) {
        boolean result = false;
        String check = path.toAbsolutePath().toString();
        Iterator<CloudModule> moduleIterator = this.getModules().values().iterator();
        while (moduleIterator.hasNext() && !result) {
            result = moduleIterator.next().getModuleJson().getFile().toAbsolutePath().toString().equals(check);
        }
        if (!result) {
            Iterator<Path> iterator = toLoad.iterator();
            while (iterator.hasNext() && !result) {
                result = iterator.next().toAbsolutePath().toString().equals(check);
            }
        }
        return result;
    }

    public Map<String, CloudModule> getModules() {
        return modules;
    }

    public Optional<CloudModule> getModule(@NotNull String name) {
        return Optional.of(getModules().get(name));
    }

    private List<CloudModule> resolveDependenciesSortedSingle(@NotNull List<CloudModule> cloudModuleDescriptionFiles,
                                                                  CloudModule javaCloudModule) {
        MutableGraph<CloudModule> graph = GraphBuilder
            .directed()
            .expectedNodeCount(cloudModuleDescriptionFiles.size())
            .allowsSelfLoops(false)
            .build();
        Map<String, CloudModule> candidateAsMap = Maps.uniqueIndex(cloudModuleDescriptionFiles,
                                                                       f -> String.format("%s:%s",
                                                                                          f.getModuleJson().getGroupId(),
                                                                                          f.getModuleJson().getName()));

        CloudModule descriptionFile = javaCloudModule;
        graph.addNode(descriptionFile);
        for (CloudModuleDependency dependency : descriptionFile.getModuleJson().getDependencies()) {
            CloudModule dependencyContainer = candidateAsMap.get(String.format("%s:%s",
                                                                                   dependency.getGroupId(),
                                                                                   dependency.getName()));
            if (dependencyContainer != null) {
                graph.putEdge(dependencyContainer, descriptionFile);
            }
        }

        List<CloudModule> sorted = new ArrayList<>();
        Map<CloudModule, Integer> integerMap = new HashMap<>();

        for (CloudModule node : graph.nodes()) {
            this.visitDependency(graph, node, integerMap, sorted, new ArrayDeque<>());
        }

        return sorted;
    }

    private List<CloudModule> resolveDependenciesSorted(@NotNull List<CloudModule> cloudModuleDescriptionFiles) {
        MutableGraph<CloudModule> graph = GraphBuilder
            .directed()
            .expectedNodeCount(cloudModuleDescriptionFiles.size())
            .allowsSelfLoops(false)
            .build();
        Map<String, CloudModule> candidateAsMap = Maps.uniqueIndex(cloudModuleDescriptionFiles,
                                                                       f -> String.format("%s:%s",
                                                                                          f.getModuleJson().getGroupId(),
                                                                                          f.getModuleJson().getName()));

        for (CloudModule descriptionFile : cloudModuleDescriptionFiles) {
            graph.addNode(descriptionFile);
            for (CloudModuleDependency dependency : descriptionFile.getModuleJson().getDependencies()) {
                CloudModule dependencyContainer = candidateAsMap.get(String.format("%s:%s",
                                                                                       dependency.getGroupId(),
                                                                                       dependency.getName()));
                if (dependencyContainer != null) {
                    graph.putEdge(dependencyContainer, descriptionFile);
                }
            }
        }
        List<CloudModule> sorted = new ArrayList<>();
        Map<CloudModule, Integer> integerMap = new HashMap<>();

        for (CloudModule node : graph.nodes()) {
            this.visitDependency(graph, node, integerMap, sorted, new ArrayDeque<>());
        }

        return sorted;
    }

    //TODO: Gibt es ein Limit für den Integer von der Map. Wenn ja die If etwas umbauen.

    private void visitDependency(Graph<CloudModule> graph,
                                 CloudModule node,
                                 Map<CloudModule, Integer> marks,
                                 List<CloudModule> sorted,
                                 Deque<CloudModule> currentIteration) {

        final Integer integer = marks.getOrDefault(node, 0);
        if (integer == 2) {
            return;
        } else if (integer == 1) {
            currentIteration.addLast(node);

            StringBuilder stringBuilder = new StringBuilder();
            for (CloudModule description : currentIteration) {
                stringBuilder.append(description.getModuleJson().getGroupId())
                             .append(":")
                             .append(description.getModuleJson().getName())
                             .append("; ");
            }
            throw new StackOverflowError("Dependency load injects itself or other injects other: " + stringBuilder.toString());
        }

        currentIteration.addLast(node);
        marks.put(node, 2);
        for (CloudModule edge : graph.successors(node)) {
            this.visitDependency(graph, edge, marks, sorted, currentIteration);
        }

        marks.put(node, 2);
        currentIteration.removeLast();
        sorted.add(node);
    }
}
