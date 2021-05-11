/*
 * FlintMC
 * Copyright (C) 2020-2021 LabyMedia GmbH and contributors
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package net.flintmc.gradle.manifest.tasks;

import net.flintmc.gradle.FlintGradleException;
import net.flintmc.gradle.extension.FlintGradleExtension;
import net.flintmc.gradle.extension.json.FlintJsonInjectionDescription;
import net.flintmc.gradle.json.JsonConverter;
import net.flintmc.gradle.manifest.cache.BoundMavenDependencies;
import net.flintmc.gradle.manifest.cache.StaticFileChecksums;
import net.flintmc.gradle.manifest.data.*;
import net.flintmc.gradle.maven.pom.MavenArtifact;
import net.flintmc.gradle.minecraft.data.environment.MinecraftVersion;
import net.flintmc.gradle.property.FlintPluginProperties;
import net.flintmc.installer.impl.repository.models.DependencyDescriptionModel;
import net.flintmc.installer.impl.repository.models.PackageModel;
import net.flintmc.installer.impl.repository.models.install.InstallInstructionModel;
import net.flintmc.installer.impl.repository.models.install.InstallInstructionTypes;
import net.flintmc.installer.impl.repository.models.install.data.DownloadFileDataModel;
import net.flintmc.installer.impl.repository.models.install.data.DownloadMavenDependencyDataModel;
import net.flintmc.installer.impl.repository.models.install.data.json.ModifyJsonFileDataModel;
import net.flintmc.installer.impl.repository.models.install.data.json.injections.ModifyJsonArrayInjection;
import net.flintmc.installer.impl.repository.models.install.data.json.injections.ModifyJsonObjectInjection;
import net.flintmc.installer.install.json.JsonFileDataInjection;
import net.flintmc.installer.install.json.JsonInjectionPath;
import net.flintmc.installer.util.OperatingSystem;
import org.gradle.api.DefaultTask;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.tasks.*;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;

/**
 * Task generating the flint manifest.json
 */
public class GenerateFlintManifestTask extends DefaultTask {
  @OutputFile
  private final File manifestFile;

  private final ManifestStaticFileInput staticFiles;

  @Nested
  private final ManifestPackageDependencyInput packageDependencies;

  @InputFile
  private final File artifactURLsCacheFile;

  @InputFile
  private final File staticFilesChecksumsCacheFile;

  private FlintGradleExtension extension;

  /**
   * Constructs a new {@link GenerateFlintManifestTask}.
   *
   * @param manifestFile                  The file to write the generated manifest to
   * @param staticFiles                   The static files to index in the manifest
   * @param packageDependencies           The packages the manifest lists as dependencies
   * @param artifactURLsCacheFile         The file to load the cached artifact URLs from
   * @param staticFilesChecksumsCacheFile The file to load the cached static checksums from
   */
  @Inject
  public GenerateFlintManifestTask(
      File manifestFile,
      ManifestStaticFileInput staticFiles,
      ManifestPackageDependencyInput packageDependencies,
      File artifactURLsCacheFile,
      File staticFilesChecksumsCacheFile
  ) {
    this.manifestFile = manifestFile;
    this.staticFiles = staticFiles;
    this.packageDependencies = packageDependencies;
    this.artifactURLsCacheFile = artifactURLsCacheFile;
    this.staticFilesChecksumsCacheFile = staticFilesChecksumsCacheFile;
  }

  /**
   * Retrieves the file to write the generated manifest to.
   *
   * @return The file to write the generated manifest to
   */
  @SuppressWarnings("unused") // Required for @InputFile on `manifestFile`
  public File getManifestFile() {
    return manifestFile;
  }

  /**
   * Retrieves the input file this task reads the maven artifact URL's from.
   *
   * @return The file this task reads the maven artifact URL's from
   */
  public File getArtifactURLsCacheFile() {
    return artifactURLsCacheFile;
  }

  /**
   * Retrieves the input file this task reads the static file checksums from.
   *
   * @return The file this task reads the static file checksums from
   */
  public File getStaticFilesChecksumsCacheFile() {
    return staticFilesChecksumsCacheFile;
  }

  /**
   * Retrieves the package dependencies for this manifest.
   *
   * @return The package dependencies for this manifest
   */
  @SuppressWarnings("unused") // Required for @Nested on `packageDependencies`
  public ManifestPackageDependencyInput getPackageDependencies() {
    packageDependencies.compute(getProject());
    return packageDependencies;
  }

  /**
   * Retrieves the static files for this manifest.
   *
   * @return The static files for this manifest
   */
  // NOTE: This method is only required for gradle to correctly calculate the up-to-date state of this task
  @Input
  public Set<ManifestStaticFile> getStaticFiles() {
    staticFiles.compute(getProject());

    Set<ManifestStaticFile> out = new HashSet<>();
    out.addAll(staticFiles.getRemoteFiles());
    out.addAll(staticFiles.getLocalFiles().values());

    return out;
  }

  /**
   * Retrieves the group for this manifest.
   *
   * @return The group for this manifest
   */
  @Input
  public String getProjectGroup() {
    return getProject().getGroup().toString();
  }

  /**
   * Retrieves the name for this manifest.
   *
   * @return The name for this manifest
   */
  @Input
  public String getProjectName() {
    return getProject().getName();
  }

  /**
   * Retrieves the description for this manifest.
   *
   * @return The description for this manifest
   */
  @Input
  public String getProjectDescription() {
    String description = getProject().getDescription();
    return description != null ? description : "A flint project";
  }

  /**
   * Retrieves the version for this manifest.
   *
   * @return The version for this manifest
   */
  @Input
  public String getProjectVersion() {
    return getProject().getVersion().toString();
  }

  /**
   * Retrieves the channel name for this manifest.
   *
   * @return The channel name for this manifest
   */
  @Input
  public String getChannel() {
    String channel = FlintPluginProperties.DISTRIBUTOR_CHANNEL.resolve(getProject());
    return channel == null ? "development" : channel;
  }

  /**
   * Retrieves the minecraft versions for this manifest.
   *
   * @return The minecraft versions for this manifest
   */
  @Input
  public Set<String> getMinecraftVersions() {
    return this.getExtension().getMinecraftVersions().stream()
        .map(MinecraftVersion::getVersion)
        .collect(Collectors.toCollection(CopyOnWriteArraySet::new));
  }

  /**
   * Retrieves the flint version for this manifest.
   *
   * @return The flint version for this manifest
   */
  @Input
  public String getFlintVersion() {
    return getExtension().getFlintVersion();
  }

  /**
   * Retrieves the authors for this manifest.
   *
   * @return The authors for this manifest
   */
  @Input
  public Set<String> getAuthors() {
    String[] authors = getExtension().getAuthors();
    return authors == null ? Collections.emptySet() : new HashSet<>(Arrays.asList(authors));
  }

  /**
   * Retrieves the flint extension controlling this manifest.
   *
   * @return The flint extension controlling this manifest
   */
  private FlintGradleExtension getExtension() {
    if (extension == null) {
      extension = getProject().getExtensions().getByType(FlintGradleExtension.class);
    }

    return extension;
  }

  @TaskAction
  public void generate() {
    if (!getArtifactURLsCacheFile().isFile()) {
      throw new IllegalStateException("Missing maven artifacts URLs cache file");
    } else if (!getStaticFilesChecksumsCacheFile().isFile()) {
      throw new IllegalStateException("Missing static file checksum cache file");
    }

    staticFiles.compute(getProject());
    packageDependencies.compute(getProject());

    // Load cached artifact URLs
    Map<ManifestMavenDependency, URI> dependencyURIs;
    try {
      dependencyURIs = BoundMavenDependencies.load(artifactURLsCacheFile);
    } catch (IOException e) {
      throw new FlintGradleException("IOException while loading cached maven artifact URLs", e);
    }

    // Load cached checksums
    StaticFileChecksums checksums;
    try {
      checksums = StaticFileChecksums.load(staticFilesChecksumsCacheFile);
    } catch (IOException e) {
      throw new FlintGradleException("IOException while loading cached static files checksums", e);
    }

    // Build package dependencies
    Set<DependencyDescriptionModel> dependencyDescriptionModels = new HashSet<>();
    for (ManifestPackageDependency dependency : packageDependencies.getDependencies()) {
      dependencyDescriptionModels.add(new DependencyDescriptionModel(
          dependency.getName(),
          dependency.getVersion(),
          dependency.getChannel()
      ));
    }

    // Build all install instructions
    Set<InstallInstructionModel> mavenInstallInstructions = buildMavenInstallInstructions(dependencyURIs);
    Set<InstallInstructionModel> staticFileInstallInstructions = buildStaticFileInstructions(checksums);
    Set<InstallInstructionModel> modifyJsonInstallInstructions = buildJsonInjectionInstructions();
    InstallInstructionModel ownInstallInstruction = buildOwnInstallInstruction();

    // Build the runtime classpath
    Set<String> runtimeClasspath = new HashSet<>();
    for (InstallInstructionModel mavenInstallInstruction : mavenInstallInstructions) {
      // Add all maven dependencies to the runtime classpath
      DownloadMavenDependencyDataModel data = mavenInstallInstruction.getData();
      if (ownInstallInstruction == null
          || !data.getPath().equals(ownInstallInstruction.<DownloadMavenDependencyDataModel>getData().getPath())) {
        // If the library does not equal ourself, add it
        runtimeClasspath.add(data.getPath());
      }
    }

    for (InstallInstructionModel staticFileInstallInstruction : staticFileInstallInstructions) {
      DownloadFileDataModel data = staticFileInstallInstruction.getData();
      if (data.getPath().endsWith(".jar")) {
        // If the static file is a jar, add it to the classpath
        runtimeClasspath.add(data.getPath());
      }
    }

    // Collect all install instructions
    List<InstallInstructionModel> allInstallInstructions = new ArrayList<>();
    if (ownInstallInstruction != null) {
      allInstallInstructions.add(ownInstallInstruction);
    }
    allInstallInstructions.addAll(mavenInstallInstructions);
    allInstallInstructions.addAll(staticFileInstallInstructions);
    allInstallInstructions.addAll(modifyJsonInstallInstructions);

    // Build package model
    PackageModel model = new PackageModel(
        getProjectGroup(),
        getProjectName(),
        getProjectDescription(),
        getProjectVersion(),
        getChannel(),
        String.join(",", getMinecraftVersions()),
        getFlintVersion(),
        getAuthors(),
        dependencyDescriptionModels,
        runtimeClasspath,
        allInstallInstructions
    );

    // Ensure the manifest file is writeable
    File manifestParentDir = manifestFile.getParentFile();
    if (!manifestParentDir.isDirectory() && !manifestParentDir.mkdirs()) {
      throw new FlintGradleException("Failed to create directory " + manifestParentDir.getAbsolutePath());
    }

    // Serialize and write the manifest file
    String json = JsonConverter.PACKAGE_MODEL_SERIALIZER.toString(model);
    try {
      Files.write(manifestFile.toPath(), json.getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new FlintGradleException("Failed to write manifest file", e);
    }
  }

  /**
   * Builds the install instructions required by the maven dependencies.
   *
   * @param mavenDependencyURIs The URI's of the maven dependencies
   * @return The install instructions of the maven dependencies
   */
  private Set<InstallInstructionModel> buildMavenInstallInstructions(
      Map<ManifestMavenDependency, URI> mavenDependencyURIs) {
    Set<InstallInstructionModel> out = new HashSet<>();

    for (Map.Entry<ManifestMavenDependency, URI> entry : mavenDependencyURIs.entrySet()) {
      MavenArtifact artifact = entry.getKey().getArtifact();

      // Construct the path relative to the root of the libraries folder
      String localPath = String.format(
          "${FLINT_LIBRARY_DIR}/%s/%s/%s/%s-%s%s.jar",
          artifact.getGroupId().replace('.', '/'),
          artifact.getArtifactId(),
          artifact.getVersion(),
          artifact.getArtifactId(),
          artifact.getVersion(),
          artifact.getClassifier() == null ? "" : "-" + artifact.getClassifier()
      );

      // Add the instruction
      out.add(new InstallInstructionModel(
          InstallInstructionTypes.DOWNLOAD_MAVEN_DEPENDENCY,
          null,
          new DownloadMavenDependencyDataModel(
              artifact.getGroupId(),
              artifact.getArtifactId(),
              artifact.getVersion(),
              artifact.getClassifier(),
              entry.getValue().toASCIIString(),
              localPath
          )
      ));
    }

    return out;
  }

  /**
   * Builds the install instruction required to install the project jar.
   *
   * @return The install instruction of the project jar
   */
  private InstallInstructionModel buildOwnInstallInstruction() {
    if (!this.getExtension().shouldInstallJar()) {
      return null;
    }

    String targetPath;

    if (getExtension().getType() == FlintGradleExtension.Type.LIBRARY) {
      // If the jar is a library, put it into the libraries folder
      targetPath = "${FLINT_LIBRARY_DIR}/" +
          getProjectGroup().replace('.', '/') + "/" +
          getProjectName() + "/" +
          getProjectVersion() + "/" +
          getProjectName() + "-" + getProjectVersion() + ".jar";
    } else {
      // The jar is a package, put it into the package dir
      targetPath = "${FLINT_PACKAGE_DIR}/" + getProjectName() + ".jar";
    }

    return new InstallInstructionModel(
        InstallInstructionTypes.DOWNLOAD_MAVEN_DEPENDENCY,
        null,
        new DownloadMavenDependencyDataModel(
            getProjectGroup(),
            getProjectName(),
            getProjectVersion(),
            null,
            "${FLINT_DISTRIBUTOR_URL}" + getChannel(),
            targetPath
        )
    );
  }

  /**
   * Builds the install instructions required by the static files.
   *
   * @param checksums The cached checksums
   * @return The install instructions of the static files
   */
  private Set<InstallInstructionModel> buildStaticFileInstructions(StaticFileChecksums checksums) {
    Set<InstallInstructionModel> out = new HashSet<>();

    // Process local files
    for (Map.Entry<File, ManifestStaticFile> entry : staticFiles.getLocalFiles().entrySet()) {
      File file = entry.getKey();
      ManifestStaticFile data = entry.getValue();

      if (!checksums.has(file)) {
        // Should not happen unless the user explicitly excluded the checksum calculation task
        throw new IllegalStateException("No cached checksum found for file " + file.getAbsolutePath());
      }

      // Add the instruction
      out.add(new InstallInstructionModel(
          InstallInstructionTypes.DOWNLOAD_FILE,
          data.getOperatingSystem(),
          new DownloadFileDataModel(
              data.getURI().toASCIIString(),
              data.getPath(),
              checksums.get(file)
          )
      ));
    }

    // Process remote files
    for (ManifestStaticFile remoteFile : staticFiles.getRemoteFiles()) {
      if (!checksums.has(remoteFile.getURI())) {
        // Should not happen unless the user explicitly excluded the checksum calculation task
        throw new IllegalStateException("No cached checksum found for URI " + remoteFile.getURI().toASCIIString());
      }

      // Add the instruction
      out.add(new InstallInstructionModel(
          InstallInstructionTypes.DOWNLOAD_FILE,
          remoteFile.getOperatingSystem(),
          new DownloadFileDataModel(
              remoteFile.getURI().toASCIIString(),
              remoteFile.getPath(),
              checksums.get(remoteFile.getURI())
          )
      ));
    }

    return out;
  }

  private Set<InstallInstructionModel> buildJsonInjectionInstructions() {
    NamedDomainObjectContainer<FlintJsonInjectionDescription> descriptions = this.getProject().getExtensions()
        .getByType(FlintGradleExtension.class).getJsonInjections().getJsonInjectionDescriptions();

    Map<String, ModifyJsonFileDataModel> models = new HashMap<>();
    for (FlintJsonInjectionDescription description : descriptions) {
      description.validate();

      JsonFileDataInjection injection;
      switch (description.getType()) {
        case MODIFY_ARRAY:
          injection = new ModifyJsonArrayInjection(
              description.getOverrideType(),
              new JsonInjectionPath(description.getPathEntries()),
              description.getInjection());
          break;

        case MODIFY_OBJECT:
          injection = new ModifyJsonObjectInjection(
              description.getOverrideType(),
              new JsonInjectionPath(description.getPathEntries()),
              description.getInjectKey(),
              description.getInjection());
          break;
        default:
          throw new IllegalStateException("Unexpected value: " + description.getType());
      }

      String key = description.getPath() + description.isPrettyPrint();
      if (models.containsKey(key)) {
        models.get(key).getInjections().add(injection);
        continue;
      }

      models.put(key, new ModifyJsonFileDataModel(
          description.getPath(),
          description.isPrettyPrint(),
          new ArrayList<>(Collections.singletonList(injection))
      ));
    }

    return models.values().stream()
        .map(model -> new InstallInstructionModel(InstallInstructionTypes.MODIFY_JSON_FILE, OperatingSystem.UNKNOWN, model))
        .collect(Collectors.toSet());
  }
}
