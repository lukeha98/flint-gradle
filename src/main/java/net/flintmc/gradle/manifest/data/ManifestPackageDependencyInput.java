package net.flintmc.gradle.manifest.data;

import net.flintmc.gradle.FlintGradleException;
import net.flintmc.gradle.extension.FlintGradleExtension;
import net.flintmc.gradle.json.JsonConverterException;
import net.flintmc.gradle.property.FlintPluginProperties;
import net.flintmc.gradle.util.Util;
import net.flintmc.installer.impl.repository.models.PackageModel;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.tasks.Input;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Cacheable list of package dependencies.
 */
public class ManifestPackageDependencyInput {
  private final Set<ManifestPackageDependency> dependencies;

  public ManifestPackageDependencyInput() {
    this.dependencies = new HashSet<>();
  }

  /**
   * Computes the input for the given project.
   *
   * @param project The project to compute the input for
   */
  public void compute(Project project) {
    Set<ResolvedArtifact> runtimeClasspath =
        project.getConfigurations().getByName("runtimeClasspath").getResolvedConfiguration().getResolvedArtifacts();

    for(ResolvedArtifact resolvedArtifact : runtimeClasspath) {
      ComponentIdentifier componentIdentifier = resolvedArtifact.getId().getComponentIdentifier();

      if(componentIdentifier instanceof ModuleComponentIdentifier) {
        PackageModel packageModel;

        // Check if the artifact is a package
        File artifactFile = resolvedArtifact.getFile();
        try {
          packageModel = Util.getPackageModelFromJar(artifactFile);
        } catch(IOException e) {
          throw new FlintGradleException(
              "Failed to check if file " + artifactFile.getAbsolutePath() + " is a package jar", e);
        } catch(JsonConverterException e) {
          throw new FlintGradleException("Failed to parse manifest.json from " + artifactFile.getAbsolutePath(), e);
        }

        if(packageModel == null) {
          // Not a package
          continue;
        }

        // Index the dependency
        dependencies.add(new ManifestPackageDependency(
            packageModel.getName(),
            packageModel.getVersion(),
            packageModel.getChannel()
        ));
      } else if(componentIdentifier instanceof ProjectComponentIdentifier) {
        ProjectComponentIdentifier projectComponent = (ProjectComponentIdentifier) componentIdentifier;

        // Try to get the dependency project
        Project dependencyProject = project.getRootProject().findProject(projectComponent.getProjectPath());
        if(dependencyProject == null) {
          project.getLogger().warn("Project {} depends on project {}, " +
                  "but it failed to resolve and thus will be excluded from the manifest dependencies",
              project.getDisplayName(),
              projectComponent.getProjectPath()
          );
          project.getLogger().warn("You will need to make sure the project is available at runtime yourself!");
          continue;
        }

        if(dependencyProject.getExtensions().findByType(FlintGradleExtension.class) == null) {
          // The dependency is not a flint project
          project.getLogger().warn(
              "Project {} depends on project {} which is not a flint project, " +
                  "thus it will be excluded from the manifest dependencies",
              project.getDisplayName(),
              dependencyProject.getDisplayName()
          );
          project.getLogger().warn("You will need to make sure the project is available at runtime yourself!");
          continue;
        }

        // Index the dependency
        dependencies.add(new ManifestPackageDependency(
            dependencyProject.getName(),
            dependencyProject.getVersion().toString(),
            FlintPluginProperties.DISTRIBUTOR_CHANNEL.require(dependencyProject)
        ));
      }
    }
  }

  /**
   * Retrieves a set of all dependencies of the project.
   *
   * @return A set of all dependencies of the project
   */
  @Input
  public Set<ManifestPackageDependency> getDependencies() {
    return dependencies;
  }
}
