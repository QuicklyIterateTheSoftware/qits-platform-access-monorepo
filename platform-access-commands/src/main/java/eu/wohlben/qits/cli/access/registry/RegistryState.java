package eu.wohlben.qits.cli.access.registry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import eu.wohlben.qits.cli.access.session.PrivateFiles;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * {@code registries.json} in the qits config directory: what {@code qits registry-login --configure}
 * set up. Its presence is the switch: the session daemon and a plain {@code qits registry-login}
 * update the files it names, and nothing when it is absent. It holds no token.
 *
 * @param npmrc         the npm user config the block lives in
 * @param mavenSettings the Maven user settings the blocks live in
 * @param npmRegistries scheme-less registry prefixes, {@code //registry.qits.example/artifacts/npm/npm/}
 * @param mavenUrl      the hosted Maven repository the {@code qits-maven-host} mirror points at
 * @param dockerConfig  the Docker client config whose {@code credHelpers} names {@code qits}; null in a
 *                      file written before Docker was set up, and then Docker is left alone. Nothing
 *                      but {@code --configure} writes it: Docker asks {@code qits docker-credential}
 *                      for the token each time, so a new token needs no write.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegistryState(String npmrc, String mavenSettings, List<String> npmRegistries, String mavenUrl,
                            String dockerConfig) {

    public static final String FILE_NAME = "registries.json";

    /** A setup without Docker, as files written before it read. */
    public RegistryState(String npmrc, String mavenSettings, List<String> npmRegistries, String mavenUrl) {
        this(npmrc, mavenSettings, npmRegistries, mavenUrl, null);
    }

    private static final ObjectMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    static Optional<RegistryState> read(Path directory) throws IOException {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(directory.resolve(FILE_NAME));
        } catch (NoSuchFileException absent) {
            return Optional.empty();
        }
        try {
            RegistryState state = JSON.readValue(bytes, RegistryState.class);
            if (state.npmrc() == null || state.mavenSettings() == null || state.npmRegistries() == null
                    || state.mavenUrl() == null) {
                throw new IOException("missing field");
            }
            return Optional.of(state);
        } catch (IOException e) {
            throw new IOException(directory.resolve(FILE_NAME) + " is not valid; run `qits registry-login --configure`");
        }
    }

    void write(Path directory) throws IOException {
        PrivateFiles.writeAtomically(directory, FILE_NAME, JSON.writeValueAsBytes(this));
    }
}
