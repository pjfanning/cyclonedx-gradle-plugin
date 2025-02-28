/*
 * This file is part of CycloneDX Gradle Plugin.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (c) OWASP Foundation. All Rights Reserved.
 */
package org.cyclonedx.gradle;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.cyclonedx.BomGeneratorFactory;
import org.cyclonedx.CycloneDxSchema;
import org.cyclonedx.exception.GeneratorException;
import org.cyclonedx.generators.json.BomJsonGenerator;
import org.cyclonedx.generators.xml.BomXmlGenerator;
import org.cyclonedx.gradle.utils.CycloneDxUtils;
import org.cyclonedx.gradle.utils.DependencyUtils;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Component;
import org.cyclonedx.model.Hash;
import org.cyclonedx.model.Metadata;
import org.cyclonedx.model.Tool;
import org.cyclonedx.parsers.JsonParser;
import org.cyclonedx.parsers.Parser;
import org.cyclonedx.parsers.XmlParser;
import org.cyclonedx.util.BomUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedConfiguration;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

public class CycloneDxTask extends DefaultTask {

    /**
     * Various messages sent to console.
     */
    private static final String MESSAGE_RESOLVING_DEPS = "CycloneDX: Resolving Dependencies";
    private static final String MESSAGE_CREATING_BOM = "CycloneDX: Creating BOM";
    private static final String MESSAGE_CALCULATING_HASHES = "CycloneDX: Calculating Hashes";
    private static final String MESSAGE_WRITING_BOM_XML = "CycloneDX: Writing BOM XML";
    private static final String MESSAGE_WRITING_BOM_JSON = "CycloneDX: Writing BOM JSON";
    private static final String MESSAGE_VALIDATING_BOM = "CycloneDX: Validating BOM";
    private static final String MESSAGE_VALIDATION_FAILURE = "The BOM does not conform to the CycloneDX BOM standard";

    private static final String DEFAULT_PROJECT_TYPE = "library";

    private MavenHelper mavenHelper;

    private final Property<String> schemaVersion;
    private final Property<String> outputName;
    private final Property<String> outputFormat;
    private final Property<String> projectType;
    private final Property<Boolean> includeBomSerialNumber;
    private final ListProperty<String> includeConfigs;
    private final ListProperty<String> skipConfigs;
    private final Property<File> destination;

    private final Map<File, List<Hash>> artifactHashes = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, MavenProject> resolvedMavenProjects = Collections.synchronizedMap(new HashMap<>());

    public CycloneDxTask() {
        schemaVersion = getProject().getObjects().property(String.class);
        schemaVersion.convention(CycloneDxSchema.Version.VERSION_14.getVersionString());

        outputName = getProject().getObjects().property(String.class);
        outputName.convention("bom");

        outputFormat = getProject().getObjects().property(String.class);
        outputFormat.convention("all");

        projectType = getProject().getObjects().property(String.class);
        projectType.convention(DEFAULT_PROJECT_TYPE);

        includeBomSerialNumber = getProject().getObjects().property(Boolean.class);
        includeBomSerialNumber.convention(true);

        includeConfigs = getProject().getObjects().listProperty(String.class);
        skipConfigs = getProject().getObjects().listProperty(String.class);

        destination = getProject().getObjects().property(File.class);
        destination.convention(getProject().getLayout().getBuildDirectory().dir("reports").get().getAsFile());
    }

    @Input
    public Property<String> getOutputName() {
        return outputName;
    }

    public void setOutputName(String output) {
        this.outputName.set(output);
    }

    @Input
    public Property<String> getOutputFormat() {
        return outputFormat;
    }

    public void setOutputFormat(String format) {
        this.outputFormat.set(format);
    }

    @Input
    public ListProperty<String> getIncludeConfigs() {
        return includeConfigs;
    }

    public void setIncludeConfigs(Collection<String> includeConfigs) {
        this.includeConfigs.addAll(includeConfigs);
    }

    @Input
    public ListProperty<String> getSkipConfigs() {
    	return skipConfigs;
    }

    public void setSkipConfigs(Collection<String> skipConfigs) {
    	this.skipConfigs.addAll(skipConfigs);
    }

    @Input
    public Property<String> getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(String schemaVersion) {
        this.schemaVersion.set(schemaVersion);
    }

    @Input
    public Property<String> getProjectType() {
        return projectType;
    }

    public void setProjectType(String projectType) {
        this.projectType.set(projectType);
    }

    @Input
    public Property<Boolean> getIncludeBomSerialNumber() {
        return includeBomSerialNumber;
    }

    public void setIncludeBomSerialNumber(boolean includeBomSerialNumber) {
        this.includeBomSerialNumber.set(includeBomSerialNumber);
    }

    @OutputDirectory
    public Property<File> getDestination() {
        return destination;
    }

    public void setDestination(File destination) {
        this.destination.set(destination);
    }

    @TaskAction
    public void createBom() {
        if (!outputFormat.get().trim().equalsIgnoreCase("all")
            && !outputFormat.get().trim().equalsIgnoreCase("xml")
            && !outputFormat.get().trim().equalsIgnoreCase("json")) {
            throw new GradleException("Unsupported output format. Must be one of all, xml, or json");
        }

        CycloneDxSchema.Version version = computeSchemaVersion();
        logParameters();
        getLogger().info(MESSAGE_RESOLVING_DEPS);
        final Set<String> builtDependencies = getProject()
                .getRootProject()
                .getSubprojects()
                .stream()
                .map(p -> p.getGroup() + ":" + p.getName() + ":" + p.getVersion())
                .collect(Collectors.toSet());

        final Metadata metadata = createMetadata();
        final Set<Configuration> configurations = getProject().getAllprojects().stream()
                .flatMap(p -> p.getConfigurations().stream())
                .filter(configuration -> shouldIncludeConfiguration(configuration) && !shouldSkipConfiguration(configuration) && DependencyUtils.canBeResolved(configuration))
                .collect(Collectors.toSet());

        final Set<Component> components = new HashSet<>();
        final Map<String, org.cyclonedx.model.Dependency> dependencies = new HashMap<>();

        for (Configuration configuration : configurations) {
            final Set<Component> componentsFromConfig = Collections.synchronizedSet(new LinkedHashSet<>());
            final ResolvedConfiguration resolvedConfiguration = configuration.getResolvedConfiguration();
            final List<String> depsFromConfig = Collections.synchronizedList(new ArrayList<>());


            final org.cyclonedx.model.Dependency moduleDependency = new org.cyclonedx.model.Dependency(metadata.getComponent().getPurl());
            final Set<ResolvedDependency> directModuleDependencies = configuration.getResolvedConfiguration()
                .getFirstLevelModuleDependencies();

            for (ResolvedDependency directModuleDependency : directModuleDependencies) {
                moduleDependency.addDependency(new org.cyclonedx.model.Dependency(generatePackageUrl(directModuleDependency)));
                dependencies.putAll(buildDependencyGraph(directModuleDependency));
            }
            dependencies.compute(metadata.getComponent().getPurl(), (k, v) -> {
                if (v == null) {
                    return moduleDependency;
                } else if (moduleDependency.getDependencies() != null) {
                    moduleDependency.getDependencies().stream().forEach(v::addDependency);
                }
                return v;
            });

            resolvedConfiguration.getResolvedArtifacts().forEach(artifact -> {
                // Don't include other resources built from this Gradle project.
                final String dependencyName = DependencyUtils.getDependencyName(artifact);
                if (builtDependencies.contains(dependencyName)) {
                    return;
                }

                depsFromConfig.add(dependencyName);

                // Convert into a Component and augment with pom metadata if available.
                final Component component = convertArtifact(artifact, version);
                augmentComponentMetadata(component, dependencyName);
                componentsFromConfig.add(component);
            });
            Collections.sort(depsFromConfig);
            components.addAll(componentsFromConfig);
        }


        writeBom(metadata, components, dependencies.values(), version);
    }

    private CycloneDxSchema.Version computeSchemaVersion() {
        CycloneDxSchema.Version version = CycloneDxUtils.schemaVersion(getSchemaVersion().get());
        mavenHelper = new MavenHelper(getLogger(), version);
        if (version == CycloneDxSchema.Version.VERSION_10) {
            setIncludeBomSerialNumber(false);
        }
        return version;
    }

    private Map<String, org.cyclonedx.model.Dependency> buildDependencyGraph(ResolvedDependency resolvedDependency) {
        final Map<String, org.cyclonedx.model.Dependency> dependencies = new HashMap<>();
        String dependencyPurl  = generatePackageUrl(resolvedDependency);
        final org.cyclonedx.model.Dependency dependency = new org.cyclonedx.model.Dependency(dependencyPurl);
        dependencies.put(dependencyPurl, dependency);
        for (ResolvedDependency childDependency : resolvedDependency.getChildren()) {
            dependency.addDependency(new org.cyclonedx.model.Dependency(generatePackageUrl(childDependency)));
            dependencies.putAll(buildDependencyGraph(childDependency));
        }
        return dependencies;
    }

    /**
     * @param dependencyName coordinate of a module dependency in the group:name:version format
     * @return the resolved maven POM file, or null upon resolve error
     */
    private MavenProject getResolvedMavenProject(String dependencyName) {
        synchronized(resolvedMavenProjects) {
            if(resolvedMavenProjects.containsKey(dependencyName)) {
                return resolvedMavenProjects.get(dependencyName);
            }
        }
        final Dependency pomDep = getProject()
            .getDependencies()
            .create(dependencyName + "@pom");
        final Configuration pomCfg = getProject()
            .getConfigurations()
            .detachedConfiguration(pomDep);

        try {
            final File pomFile = pomCfg.resolve().stream().findFirst().orElse(null);
            if(pomFile != null) {
                final MavenProject project = mavenHelper.readPom(pomFile);
                resolvedMavenProjects.put(dependencyName, project);
                if (project != null) {
                    Model model = mavenHelper.resolveEffectivePom(pomFile, getProject());
                    if (model != null) {
                        project.setLicenses(model.getLicenses());
                    }

                    return project;
                }
            }
        } catch(Exception err) {
            getLogger().error("Unable to resolve POM for " + dependencyName + ": " + err);
        }
        resolvedMavenProjects.put(dependencyName, null);
        return null;
    }

    private void augmentComponentMetadata(Component component, String dependencyName) {
        final MavenProject project = getResolvedMavenProject(dependencyName);

        if(project != null) {
            if(project.getOrganization() != null) {
                component.setPublisher(project.getOrganization().getName());
            }
            component.setDescription(project.getDescription());
            component.setLicenseChoice(mavenHelper.resolveMavenLicenses(project.getLicenses()));
            // Update external references by the resolved POM
            mavenHelper.extractMetadata(project, component);
        }
    }

    /**
     * Converts a MavenProject into a Metadata object.
     * @return a CycloneDX Metadata object
     */
    protected Metadata createMetadata() {
        final Project project = getProject();
        final Properties properties = readPluginProperties();
        final Metadata metadata = new Metadata();
        final Tool tool = new Tool();
        tool.setVendor(properties.getProperty("vendor"));
        tool.setName(properties.getProperty("name"));
        tool.setVersion(properties.getProperty("version"));
        // TODO: Attempt to add hash values from the current mojo
        metadata.addTool(tool);

        TreeMap<String, String> qualifiers = new TreeMap<>();
        qualifiers.put("type",  "jar");

        final Component component = new Component();
        component.setGroup((StringUtils.trimToNull(project.getGroup().toString()) != null) ? project.getGroup().toString() : null);
        component.setName(project.getName());
        component.setVersion(project.getVersion().toString());
        component.setType(resolveProjectType());
        component.setPurl(generatePackageUrl(project.getGroup().toString(), project.getName(), project.getVersion().toString(), qualifiers));
        component.setBomRef(component.getPurl());
        metadata.setComponent(component);
        return metadata;
    }

    private Properties readPluginProperties() {
        final Properties props = new Properties();
        try {
            props.load(this.getClass().getClassLoader().getResourceAsStream("plugin.properties"));
        } catch (NullPointerException | IOException e) {
            getLogger().warn("Unable to load plugin.properties", e);
        }
        return props;
    }

    private Component.Type resolveProjectType() {
        for (Component.Type type: Component.Type.values()) {
            if (type.getTypeName().equalsIgnoreCase(getProjectType().get())) {
                return type;
            }
        }
        getLogger().warn("Invalid project type. Defaulting to 'library'");
        getLogger().warn("Valid types are:");
        for (Component.Type type: Component.Type.values()) {
            getLogger().warn("  " + type.getTypeName());
        }
        return Component.Type.LIBRARY;
    }

    private Component convertArtifact(ResolvedArtifact artifact, CycloneDxSchema.Version schemaVersion) {
        final Component component = new Component();
        component.setGroup(artifact.getModuleVersion().getId().getGroup());
        component.setName(artifact.getModuleVersion().getId().getName());
        component.setVersion(artifact.getModuleVersion().getId().getVersion());
        component.setType(Component.Type.LIBRARY);
        getLogger().debug(MESSAGE_CALCULATING_HASHES);
        List<Hash> hashes = artifactHashes.computeIfAbsent(artifact.getFile(), f -> {
            try {
                return BomUtils.calculateHashes(f, schemaVersion);
            } catch(IOException e) {
                getLogger().error("Error encountered calculating hashes", e);
            }
            return Collections.emptyList();
        });
        component.setHashes(hashes);

        final String packageUrl = generatePackageUrl(artifact);
        component.setPurl(packageUrl);

        if (schemaVersion.getVersion() >= 1.1) {
            component.setModified(mavenHelper.isModified(artifact));
            component.setBomRef(packageUrl);
        }

        if (mavenHelper.isDescribedArtifact(artifact)) {
            final MavenProject project = mavenHelper.extractPom(artifact);
            if (project != null) {
                mavenHelper.getClosestMetadata(artifact, project, component);
            }
        }

        return component;
    }

    private boolean shouldIncludeConfiguration(Configuration configuration) {
        return getIncludeConfigs().get().isEmpty() || getIncludeConfigs().get().contains(configuration.getName());
    }

    private boolean shouldSkipConfiguration(Configuration configuration) {
        return getSkipConfigs().get().contains(configuration.getName());
    }

    private String generatePackageUrl(final ResolvedDependency dependency) {
        TreeMap<String, String> qualifiers = new TreeMap<>();
        qualifiers.put("type",  "jar");
        return generatePackageUrl(dependency.getModuleGroup(),
                    dependency.getModuleName(),
                    dependency.getModuleVersion(),
                    qualifiers);
    }

    private String generatePackageUrl(final ResolvedArtifact artifact) {
        TreeMap<String, String> qualifiers = new TreeMap<>();
        qualifiers.put("type",  "jar");
        if (artifact.getClassifier() != null) {
            qualifiers.put("classifier", artifact.getClassifier());
        }
        return generatePackageUrl(artifact.getModuleVersion().getId().getGroup(),
                artifact.getModuleVersion().getId().getName(),
                artifact.getModuleVersion().getId().getVersion(),
                qualifiers );
    }

    private String generatePackageUrl(String groupId, String artifactId, String version, TreeMap<String, String> qualifiers) {
        try {
            return new PackageURL(PackageURL.StandardTypes.MAVEN, groupId, artifactId, version, qualifiers, null).canonicalize();
        } catch(MalformedPackageURLException e) {
            getLogger().debug("An unexpected issue occurred attempting to create a PackageURL for "
                    + groupId + ":" + artifactId + ":" + version, e);
        }
        return null;
    }

    /**
     * Ported from Maven plugin.
     * @param metadata The CycloneDX metadata object
     * @param components The CycloneDX components extracted from gradle dependencies
     * @param version The CycloneDX schema version
     */
    protected void writeBom(Metadata metadata, Set<Component> components, Collection<org.cyclonedx.model.Dependency> dependencies,
                            CycloneDxSchema.Version version) throws GradleException{
        try {
            getLogger().info(MESSAGE_CREATING_BOM);
            final Bom bom = new Bom();

            boolean includeSerialNumber = getBooleanParameter("cyclonedx.includeBomSerialNumber", getIncludeBomSerialNumber().get());

            if (CycloneDxSchema.Version.VERSION_10 != version && includeSerialNumber) {
                bom.setSerialNumber("urn:uuid:" + UUID.randomUUID());
            }
            bom.setMetadata(metadata);
            bom.setComponents(new ArrayList<>(components));
            bom.setDependencies(new ArrayList<>(dependencies));
            if (outputFormat.get().equals("all") || outputFormat.get().equals("xml")) {
                writeXMLBom(version, bom);
            }
            if (CycloneDxUtils.schemaVersion(getSchemaVersion().get()).getVersion() >= 1.2 && (outputFormat.get().equals("all") || outputFormat.get().equals("json"))) {
                writeJSONBom(version, bom);
            }
        } catch (GeneratorException | ParserConfigurationException | IOException e) {
            throw new GradleException("An error occurred executing " + this.getClass().getName(), e);
        }
    }

    private void writeXMLBom(final CycloneDxSchema.Version schemaVersion, final Bom bom)
            throws GeneratorException, ParserConfigurationException, IOException {
        final BomXmlGenerator bomGenerator = BomGeneratorFactory.createXml(schemaVersion, bom);
        bomGenerator.generate();
        final String bomString = bomGenerator.toXmlString();
        final File bomFile = new File(getDestination().get(), getOutputName().get() + ".xml");
        getLogger().info(MESSAGE_WRITING_BOM_XML);
        FileUtils.write(bomFile, bomString, StandardCharsets.UTF_8, false);
        getLogger().info(MESSAGE_VALIDATING_BOM);
        final Parser bomParser = new XmlParser();
        try {
            if (!bomParser.isValid(bomFile, schemaVersion)) {
                throw new GradleException(MESSAGE_VALIDATION_FAILURE);
            }
        } catch (Exception e) { // Changed to Exception.
            // Gradle will erroneously report "exception IOException is never thrown in body of corresponding try statement"
            throw new GradleException(MESSAGE_VALIDATION_FAILURE, e);
        }
    }

    private void writeJSONBom(final CycloneDxSchema.Version schemaVersion, final Bom bom) throws IOException {
        final BomJsonGenerator bomGenerator = BomGeneratorFactory.createJson(schemaVersion, bom);
        final String bomString = bomGenerator.toJsonString();
        final File bomFile = new File(getDestination().get(), getOutputName().get() + ".json");
        getLogger().info(MESSAGE_WRITING_BOM_JSON);
        FileUtils.write(bomFile, bomString, StandardCharsets.UTF_8, false);
        getLogger().info(MESSAGE_VALIDATING_BOM);
        final Parser bomParser = new JsonParser();
        try {
            if (!bomParser.isValid(bomFile, schemaVersion)) {
                throw new GradleException(MESSAGE_VALIDATION_FAILURE);
            }
        } catch (Exception e) { // Changed to Exception.
            // Gradle will erroneously report "exception IOException is never thrown in body of corresponding try statement"
            throw new GradleException(MESSAGE_VALIDATION_FAILURE, e);
        }
    }

    private boolean getBooleanParameter(String parameter, boolean defaultValue) {
        final Project project = super.getProject();
        if (project.hasProperty(parameter)) {
            final Object o = project.getProperties().get(parameter);
            if (o instanceof String) {
                return Boolean.parseBoolean((String)o);
            }
        }
        return defaultValue;
    }

    protected void logParameters() {
        if (getLogger().isInfoEnabled()) {
            getLogger().info("CycloneDX: Parameters");
            getLogger().info("------------------------------------------------------------------------");
            getLogger().info("schemaVersion          : " + schemaVersion.get());
            getLogger().info("includeBomSerialNumber : " + includeBomSerialNumber.get());
            getLogger().info("includeConfigs         : " + includeConfigs.get());
            getLogger().info("skipConfigs            : " + skipConfigs.get());
            getLogger().info("destination            : " + destination.get());
            getLogger().info("outputName             : " + outputName.get());
            getLogger().info("------------------------------------------------------------------------");
        }
    }


}
