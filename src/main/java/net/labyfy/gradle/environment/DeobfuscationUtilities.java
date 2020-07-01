package net.labyfy.gradle.environment;

import net.labyfy.gradle.java.exec.JavaExecutionHelper;
import net.labyfy.gradle.maven.MavenArtifactDownloader;
import net.labyfy.gradle.maven.SimpleMavenRepository;
import net.labyfy.gradle.minecraft.MinecraftRepository;
import org.apache.http.client.HttpClient;

/**
 * Class holding utilities useful during deobfuscation
 */
public class DeobfuscationUtilities {
    private final MavenArtifactDownloader downloader;
    private final MinecraftRepository minecraftRepository;
    private final SimpleMavenRepository internalRepository;
    private final HttpClient httpClient;
    private final EnvironmentCacheFileProvider cacheFileProvider;
    private final JavaExecutionHelper javaExecutionHelper;

    /**
     * Constructs a new pack of deobfuscation utilities.
     *
     * @param downloader The maven artifact download to make available to the deobfuscation environment
     * @param minecraftRepository The repository where minecraft artifacts can be found and should be places
     * @param internalRepository The repository where minecraft dependencies can be found and internal dependencies
     *                           should be placed
     * @param httpClient The HTTP client which should be used for downloads during deobfuscation, may be null
     *                   when operating in offline mode
     * @param cacheFileProvider The cache file provider to make available to the deobfuscation environment
     * @param javaExecutionHelper The execution helper to use for invoking java processes
     */
    public DeobfuscationUtilities(
            MavenArtifactDownloader downloader,
            MinecraftRepository minecraftRepository,
            SimpleMavenRepository internalRepository,
            HttpClient httpClient,
            EnvironmentCacheFileProvider cacheFileProvider,
            JavaExecutionHelper javaExecutionHelper
    ) {
        this.downloader = downloader;
        this.minecraftRepository = minecraftRepository;
        this.internalRepository = internalRepository;
        this.httpClient = httpClient;
        this.cacheFileProvider = cacheFileProvider;
        this.javaExecutionHelper = javaExecutionHelper;
    }

    /**
     * Retrieves the downloader used for downloading maven artifacts
     *
     * @return The downloader used for downloading maven artifacts
     */
    public MavenArtifactDownloader getDownloader() {
        return downloader;
    }

    /**
     * Retrieves the repository where minecraft artifacts can be found.
     *
     * @return The repository where minecraft artifacts can be found
     */
    public MinecraftRepository getMinecraftRepository() {
        return minecraftRepository;
    }

    /**
     * Retrieves the repository where minecraft and internal dependencies can be found
     *
     * @return Repositories where dependencies required for obfuscation can be found
     */
    public SimpleMavenRepository getInternalRepository() {
        return internalRepository;
    }

    /**
     * Retrieves the HTTP client which should be used for downloads during deobfuscation.
     *
     * @return The HTTP client which should be used
     */
    public HttpClient getHttpClient() {
        return httpClient;
    }

    /**
     * Retrieves the cache file provider which should be user for caching files.
     *
     * @return The cache file provider
     */
    public EnvironmentCacheFileProvider getCacheFileProvider() {
        return cacheFileProvider;
    }

    /**
     * Retrieves the helper for executing java processes.
     *
     * @return The helper for executing java processes
     */
    public JavaExecutionHelper getJavaExecutionHelper() {
        return javaExecutionHelper;
    }
}
