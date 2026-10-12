package com.rewardsiq.publish.batch;

import com.rewardsiq.common.config.RewardsIqProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.stereotype.Component;

/** Writes to rewards.batch.directory; temp file + atomic move so readers never see a half-written file. */
@Component
public class LocalDirectoryFileSink implements FileSink {

    private final Path directory;

    public LocalDirectoryFileSink(RewardsIqProperties props) {
        this.directory = Path.of(props.batch().directory());
    }

    @Override
    public void write(String fileName, byte[] content) {
        try {
            Files.createDirectories(directory);
            Path tmp = Files.createTempFile(directory, ".tmp-", ".part");
            Files.write(tmp, content);
            Files.move(tmp, directory.resolve(fileName), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + fileName + " to " + directory, e);
        }
    }
}
