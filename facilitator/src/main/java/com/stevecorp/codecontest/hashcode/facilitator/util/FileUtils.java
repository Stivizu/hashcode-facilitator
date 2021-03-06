package com.stevecorp.codecontest.hashcode.facilitator.util;

import org.zeroturnaround.zip.ZipUtil;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static java.text.MessageFormat.format;

public class FileUtils {

    public static Path getFolderFromResources(final String folderName) {
        try {
            final URL folderURL = FileUtils.class.getClassLoader().getResource(folderName);
            if (folderURL == null) {
                throw new RuntimeException(format(
                        "Failed to retrieve the ''{0}'' folder from the resources folder - make sure it exists!",
                        folderName));
            }
            final Path folderPath = Paths.get(folderURL.toURI());
            if (!Files.isDirectory(folderPath)) {
                throw new RuntimeException(format("''{0}'' is not a folder!", folderName));
            }
            return folderPath;
        } catch (final URISyntaxException e) {
            throw new RuntimeException(format(
                    "Failed to retrieve the ''{0}'' folder from the resources folder!",
                    folderName), e);
        }
    }

    public static Path getFolder(final String fullFolderPath) {
        final Path folderPath = Paths.get(fullFolderPath);
        if (!Files.exists(folderPath)) {
            throw new RuntimeException(format("''{0}'' does not exist!", fullFolderPath));
        }
        if (!Files.isDirectory(folderPath)) {
            throw new RuntimeException(format("''{0}'' is not a folder!", fullFolderPath));
        }
        return folderPath;
    }

    public static List<String> readFileContents(final Path filePath) {
        try {
            return Files.readAllLines(filePath);
        } catch (final Exception e) {
            throw new RuntimeException(format("Unable to read file contents of file ''{0}''",
                    toFileName(filePath)), e);
        }
    }

    public static List<Path> getFilePathsFromFolder(final Path folderPath, final Predicate<String> fileNameMatcher) {
        try {
            return Files.list(folderPath)
                    .filter(file -> !Files.isDirectory(file))
                    .filter(file -> fileNameMatcher.test(toFileName(file)))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (final IOException e) {
            throw new RuntimeException(format("Unable to read files for folder ''{0}''",
                    toFileName(folderPath)), e);
        }
    }

    public static void writeToFile(final Path destinationFolderPath, final Path inputFile, final List<String> content) {
        try {
            final String inputFileName = toFileName(inputFile);
            final String outputFileName = inputFileName.contains(".")
                    ? inputFileName.substring(0, inputFileName.lastIndexOf(".")) + ".out"
                    : inputFileName + ".out";
            final Path outputFilePath = destinationFolderPath.resolve(outputFileName);
            Files.write(outputFilePath, content);
        } catch (final IOException e) {
            throw new RuntimeException(format("Unable to write algorithm output for input file ''{0}'' to the output folder ''{1}''",
                    toFileName(inputFile), destinationFolderPath.toString()), e);
        }
    }

    public static String toFileName(final Path filePath) {
        return filePath.getFileName().toString();
    }

    public static void cleanFolderContents(final Path folderPath) {
        try {
            org.apache.commons.io.FileUtils.cleanDirectory(folderPath.toFile());
        } catch (final Exception e) {
            throw new RuntimeException(format("Unable to clean the directory contents for folder: ''{0}''",
                    toFileName(folderPath)), e);
        }
    }

    public static Path getSrcMainJavaLocationFromClass(final Class<?> clazz) {
        try {
            return Paths.get(clazz.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .getParent().getParent()
                    .resolve("src/main/java");
        } catch (final URISyntaxException e) {
            throw new RuntimeException(format("Unable to derive the location of the src/main/java location through class ''{0}''",
                    ClassUtils.simpleName(clazz)));
        }
    }

    public static void zipFilesToFolder(final Path folderToZip, final Path outputFolder) {
        try {
            final Path outputFilePath = outputFolder.resolve("sources.zip");
            ZipUtil.pack(folderToZip.toFile(), outputFilePath.toFile());
        } catch (final Exception e) {
            System.out.println(format("Unable to write sources (''{0}'') zip to output folder (''{1}'')",
                    toFileName(folderToZip), toFileName(outputFolder)));
        }
    }

}
