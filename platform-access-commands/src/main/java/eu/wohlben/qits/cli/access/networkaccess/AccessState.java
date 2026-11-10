package eu.wohlben.qits.cli.access.networkaccess;

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
import java.util.Optional;

/**
 * {@code access.json} in the qits config directory, beside {@code registries.json}: what {@code qits
 * network-access up} set up (epic qits-1977). Its {@code proxy} is the switch: while it names one,
 * every platform call of this workstation goes through the companion there ({@code
 * CompanionProxy}). {@code down} drops the proxy and keeps the peer, so the next {@code up} reuses
 * the device the companion's key is registered as; {@code down --revoke} deletes the file. It holds
 * no secret: the private key never leaves the companion's volume.
 *
 * @param peerId  the device peer the companion's key is registered as
 * @param proxy   the companion's address, {@code http://127.0.0.1:47813}; null after {@code down}
 * @param image   the image the companion runs from
 * @param version that image's tag
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccessState(String peerId, String proxy, String image, String version) {

    public static final String FILE_NAME = "access.json";

    private static final ObjectMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    /** The state, or empty when there is none. A file that does not parse throws. */
    public static Optional<AccessState> read(Path directory) throws IOException {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(directory.resolve(FILE_NAME));
        } catch (NoSuchFileException absent) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(JSON.readValue(bytes, AccessState.class));
        } catch (IOException e) {
            throw new IOException(directory.resolve(FILE_NAME) + " is not valid; run `qits network-access up`");
        }
    }

    /** {@link #read}, with a file that cannot be read taken as none: what the proxy selector needs. */
    public static Optional<AccessState> readQuietly(Path directory) {
        try {
            return read(directory);
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    void write(Path directory) throws IOException {
        PrivateFiles.writeAtomically(directory, FILE_NAME, JSON.writeValueAsBytes(this));
    }

    static void delete(Path directory) throws IOException {
        Files.deleteIfExists(directory.resolve(FILE_NAME));
    }
}
