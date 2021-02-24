package com.stevecorp.codecontest.hashcode.facilitator;

import com.stevecorp.codecontest.hashcode.facilitator.configurator.algorithm.Algorithm;
import com.stevecorp.codecontest.hashcode.facilitator.configurator.algorithm.AlgorithmParameter;
import com.stevecorp.codecontest.hashcode.facilitator.configurator.algorithm.AlgorithmSpecification;
import com.stevecorp.codecontest.hashcode.facilitator.configurator.algorithm.algorithm.BasicAlgorithm;
import com.stevecorp.codecontest.hashcode.facilitator.configurator.algorithm.algorithm.ParameterizedAlgorithm;
import com.stevecorp.codecontest.hashcode.facilitator.configurator.algorithm.parameter.BoundedParameter;
import com.stevecorp.codecontest.hashcode.facilitator.configurator.algorithm.parameter.EnumeratedParameter;
import com.stevecorp.codecontest.hashcode.facilitator.configurator.input.InputModel;
import com.stevecorp.codecontest.hashcode.facilitator.configurator.input.InputParser;
import com.stevecorp.codecontest.hashcode.facilitator.configurator.input.InputSpecifier;
import com.stevecorp.codecontest.hashcode.facilitator.configurator.output.OutputModel;
import com.stevecorp.codecontest.hashcode.facilitator.configurator.output.OutputProducer;
import com.stevecorp.codecontest.hashcode.facilitator.configurator.output.OutputValidator;
import com.stevecorp.codecontest.hashcode.facilitator.configurator.score.ScoreCalculator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.stevecorp.codecontest.hashcode.facilitator.configurator.input.InputSpecifier.ALL_INPUT_FILES;
import static com.stevecorp.codecontest.hashcode.facilitator.configurator.input.InputSpecifier.SELECTED_INPUT_FILES;
import static com.stevecorp.codecontest.hashcode.facilitator.util.ClassUtils.constructInstance;
import static com.stevecorp.codecontest.hashcode.facilitator.util.ClassUtils.simpleName;
import static com.stevecorp.codecontest.hashcode.facilitator.util.CollectionUtils.join;
import static com.stevecorp.codecontest.hashcode.facilitator.util.FileUtils.cleanFolderContents;
import static com.stevecorp.codecontest.hashcode.facilitator.util.FileUtils.getFilePathsFromFolder;
import static com.stevecorp.codecontest.hashcode.facilitator.util.FileUtils.getFolder;
import static com.stevecorp.codecontest.hashcode.facilitator.util.FileUtils.getFolderFromResources;
import static com.stevecorp.codecontest.hashcode.facilitator.util.FileUtils.getSrcMainJavaLocationFromClass;
import static com.stevecorp.codecontest.hashcode.facilitator.util.FileUtils.readFileContents;
import static com.stevecorp.codecontest.hashcode.facilitator.util.FileUtils.toFileName;
import static com.stevecorp.codecontest.hashcode.facilitator.util.FileUtils.writeToFile;
import static com.stevecorp.codecontest.hashcode.facilitator.util.FileUtils.zipFilesToFolder;
import static java.text.MessageFormat.format;
import static java.util.Comparator.comparingLong;

@SuppressWarnings({ "unused", "unchecked", "rawtypes", "FieldCanBeLocal", "OptionalUsedAsFieldOrParameterType" })
public class HashCodeFacilitator<T extends InputModel, U extends OutputModel> {

    private static final Path DEFAULT_INPUT_FOLDER = getFolderFromResources("input");
    private static final Path DEFAULT_OUTPUT_FOLDER = getFolderFromResources("output");
    private static final int DEFAULT_NUMBER_OF_SUBOPTIMAL_SOLUTIONS_TO_SHOW = 4;
    private static final int PROGRESS_BAR_LENGTH = 50;

    private final boolean debugMode;
    private final InputSpecifier inputSpecifier;
    private final List<String> inputFileNames;
    private final InputParser<T> inputParser;
    private final List<AlgorithmSpecification<T, U>> algorithmSpecifications;
    private final Optional<OutputValidator<T, U>> outputValidator;
    private final ScoreCalculator<T, U> scoreCalculator;
    private final OutputProducer<U> outputProducer;
    private final Path inputFolder;
    private final Path outputFolder;
    private final int numberOfSuboptimalSolutionsToShow;

    private HashCodeFacilitator(final ConfigBuilder<T, U> builder) {
        this.debugMode = builder.debugMode;
        this.inputSpecifier = builder.inputSpecifier;
        this.inputFileNames = builder.inputFileNames;
        this.inputParser = constructInstance(builder.inputParserClass);
        this.algorithmSpecifications = builder.algorithmSpecifications;
        this.outputValidator = Optional.ofNullable(constructInstance(builder.outputValidatorClass));
        this.scoreCalculator = constructInstance(builder.scoreCalculatorClass);
        this.outputProducer = constructInstance(builder.outputProducerClass);
        this.inputFolder = builder.inputFolder != null ? builder.inputFolder : DEFAULT_INPUT_FOLDER;
        this.outputFolder = builder.outputFolder != null ? builder.outputFolder : DEFAULT_OUTPUT_FOLDER;
        this.numberOfSuboptimalSolutionsToShow = builder.numberOfSuboptimalSolutionsToShow;
    }

    public static Configurator_InputSpecifier<?, ?> configurator() {
        return new ConfigBuilder<>().configurator();
    }

    public void run() {
        preFacilitatorSetup();
        for (final Path inputFilePath : getInputFilePaths()) {
            System.out.println(format("Processing input file: ''{0}''", toFileName(inputFilePath)));
            final T input = parseInputFile(inputFilePath);
            final ScoreTracker<T, U> scoreTracker = new ScoreTracker<>(debugMode, getNumberOfScenarios(), numberOfSuboptimalSolutionsToShow);
            for (final AlgorithmSpecification<T, U> algorithmSpecification : algorithmSpecifications) {
                final Class<? extends Algorithm> algorithmClass = algorithmSpecification.getAlgorithmClass();
                if (algorithmClass.getSuperclass() == BasicAlgorithm.class) {
                    handleBasicAlgorithm(algorithmClass, input, scoreTracker);
                } else if (algorithmClass.getSuperclass() == ParameterizedAlgorithm.class) {
                    handleParameterizedAlgorithm(algorithmClass, algorithmSpecification, input, scoreTracker);
                } else {
                    throw new RuntimeException(format("Unexpected algorithm type: ''{0}''", simpleName(algorithmClass)));
                }
            }
            scoreTracker.printReport();
            writeOptimalSolutionToOutputFolder(inputFilePath, scoreTracker);
        }
        writeSourcesZipToOutputFolder();
    }

    private void preFacilitatorSetup() {
        if (!outputFolder.equals(DEFAULT_OUTPUT_FOLDER)) {
            cleanFolderContents(outputFolder);
        }
    }

    private List<Path> getInputFilePaths() {
        return getFilePathsFromFolder(inputFolder, fileName ->
                inputSpecifier == ALL_INPUT_FILES || inputFileNames.stream().anyMatch(fileName::contains));
    }

    private T parseInputFile(final Path inputFilePath) {
        return inputParser.parseInput(readFileContents(inputFilePath));
    }

    private long getNumberOfScenarios() {
        long numberOfScenarios = 0;
        numberOfScenarios += algorithmSpecifications.stream()
                .filter(algorithmSpecification -> algorithmSpecification.getAlgorithmClass().getSuperclass() == BasicAlgorithm.class)
                .count();
        numberOfScenarios += algorithmSpecifications.stream()
                .filter(algorithmSpecification -> algorithmSpecification.getAlgorithmClass().getSuperclass() == ParameterizedAlgorithm.class)
                .mapToLong(algorithmSpecification -> algorithmSpecification.getParameters().stream()
                        .mapToLong(AlgorithmParameter::getNumberOfScenarios)
                        .reduce(1, Math::multiplyExact))
                .sum();
        return numberOfScenarios;
    }

    private void handleBasicAlgorithm(
            final Class<? extends Algorithm> algorithmClass,
            final T input,
            final ScoreTracker<T, U> scoreTracker) {
        final Algorithm<T, U> algorithm = constructInstance(algorithmClass);
        doAlgorithmIteration(input, algorithm, scoreTracker);
    }

    private void handleParameterizedAlgorithm(
            final Class<? extends Algorithm> algorithmClass,
            final AlgorithmSpecification<T, U> algorithmSpecification,
            final T input,
            final ScoreTracker<T, U> scoreTracker
    ) {
        final ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        final ParameterStreamer parameterStreamer = new ParameterStreamer(algorithmSpecification.getParameters());
        while (parameterStreamer.hasNext()) {
            final ParameterizedAlgorithm<T, U> parameterizedAlgorithm = (ParameterizedAlgorithm<T, U>) constructInstance(algorithmClass);
            final Map<String, Object> iterationParameters = parameterStreamer.next();
            parameterizedAlgorithm.handleParameters(iterationParameters);
            executorService.execute(() -> doAlgorithmIteration(input, parameterizedAlgorithm, Optional.of(iterationParameters), scoreTracker));
        }
        executorService.shutdown();
        try {
            final boolean terminated = executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (final Exception e) {
            throw new RuntimeException("Something went wrong during multi-threaded algorithm execution", e);
        }
    }

    private ParameterState<?> toParameterState(final AlgorithmParameter parameter) {
        if (parameter instanceof BoundedParameter) {
            return new ParameterState_Bounded((BoundedParameter) parameter);
        }
        if (parameter instanceof EnumeratedParameter) {
            return new ParameterState_Enumerated((EnumeratedParameter) parameter);
        }
        throw new RuntimeException(format("Unsupported algorithm parameter type: ''{}''", simpleName(parameter)));
    }

    private Map<String, Object> initializeParameterMap(final List<ParameterState<?>> parameterStates) {
        return parameterStates.stream()
                .collect(Collectors.toMap(parameterState -> parameterState.parameter.getName(), ParameterState::current));
    }

    private void doAlgorithmIteration(
            final T input,
            final Algorithm<T, U> algorithm,
            final ScoreTracker<T, U> scoreTracker
    ) {
        doAlgorithmIteration(input, algorithm, Optional.empty(), scoreTracker);
    }

    private void doAlgorithmIteration(
            final T input,
            final Algorithm<T, U> algorithm,
            final Map<String, Object> parameterPermutation,
            final ScoreTracker<T, U> scoreTracker
    ) {
        doAlgorithmIteration(input, algorithm, Optional.of(parameterPermutation), scoreTracker);
    }

    private void doAlgorithmIteration(
            final T input,
            final Algorithm<T, U> algorithm,
            final Optional<Map<String, Object>> parameterPermutation,
            final ScoreTracker<T, U> scoreTracker
    ) {
        final T clonedInput = input.cloneInput();
        final U output = algorithm.solve(clonedInput);
        outputValidator.ifPresent(validator -> validator.validateOutput(clonedInput, output));
        final long score = scoreCalculator.calculateScore(clonedInput, output);
        parameterPermutation.ifPresentOrElse(
                permutation -> scoreTracker.handleUpdate(score, output, algorithm.getClass(), permutation),
                () -> scoreTracker.handleUpdate(score, output, algorithm.getClass()));
    }

    private void writeOptimalSolutionToOutputFolder(final Path inputFilePath, final ScoreTracker<T, U> scoreTracker) {
        writeToFile(outputFolder, inputFilePath, outputProducer.produceOutput((U) scoreTracker.getBestOutput().output));
    }

    private void writeSourcesZipToOutputFolder() {
        final Class<?> algorithmClass = algorithmSpecifications.get(0).getAlgorithmClass();
        final Path srcMainJavaPath = getSrcMainJavaLocationFromClass(algorithmClass);
        zipFilesToFolder(srcMainJavaPath, outputFolder);
    }

    /**************************************************************************************************************
     ***   Class to track the best algorithm per input (with additional details)                                ***
     **************************************************************************************************************/

    public static class ScoreTracker<T extends InputModel, U extends OutputModel> {

        final boolean debugMode;
        final long numberOfScenarios;
        final long numberOfResultsToShow;

        long progress;
        final List<ScoreTracker_Element> outputs = new ArrayList<>();

        ScoreTracker(final boolean debugMode, final Long numberOfScenarios, final int numberOfSuboptimalSolutionsToShow) {
            this.debugMode = debugMode;
            this.numberOfScenarios = numberOfScenarios;
            this.numberOfResultsToShow = numberOfSuboptimalSolutionsToShow + 1;
            this.progress = 0;
        }

        synchronized void handleUpdate(final long score, final U output, final Class<? extends Algorithm> algorithmClass) {
            handleUpdate(score, output, algorithmClass, null);
        }

        synchronized void handleUpdate(final long score, final U output, final Class<? extends Algorithm> algorithmClass, final Map<String, Object> parameters) {
            progress++;
            if (outputs.size() < numberOfResultsToShow) {
                outputs.add(new ScoreTracker_Element(score, output, algorithmClass, parameters));
                outputs.sort(comparingLong(ScoreTracker_Element::getScore));
            } else if (score > outputs.get(0).score) {
                outputs.set(0, new ScoreTracker_Element(score, output, algorithmClass, parameters));
                outputs.sort(comparingLong(ScoreTracker_Element::getScore));
            }
            if (!debugMode) {
                printProgressBar(progress, numberOfScenarios);
            }
        }

        private void printProgressBar(final long currentScenarioIndex, final long totalNumberOfScenarios) {
            final double percentualProgress = 1.0 * currentScenarioIndex / totalNumberOfScenarios;
            final long progressBarProgress = (long) Math.floor(PROGRESS_BAR_LENGTH * percentualProgress);
            final StringBuilder progressBar = new StringBuilder();
            progressBar.append('[');
            IntStream.range(0, (int) progressBarProgress).forEach(index -> progressBar.append("="));
            IntStream.range(0, (int) (PROGRESS_BAR_LENGTH - progressBarProgress)).forEach(index -> progressBar.append(" "));
            progressBar.append("]");
            System.out.print(format("\r{0} {1}%",
                    progressBar.toString(), percentualProgress * 100));
            if (currentScenarioIndex < totalNumberOfScenarios) {
                System.out.print(format(" ({0}/{1})", currentScenarioIndex, numberOfScenarios));
            }
            if (currentScenarioIndex == totalNumberOfScenarios) {
                System.out.print("\n");
            }
        }

        void printReport() {
            System.out.println("Optimal solution:");
            printReportPart(getBestOutput(), false);
            if (numberOfResultsToShow > 1) {
                System.out.println("Suboptimal solutions:");
                IntStream.range(0, outputs.size() - 1)
                        .map(index -> outputs.size() - 2 - index)
                        .forEach(index -> printReportPart(outputs.get(index), index != 0));
            }
            System.out.print("\n");
        }

        private void printReportPart(final ScoreTracker_Element<U> output, final boolean printSeparator) {
            System.out.println(format("\tAlgorithm: {0}", simpleName(output.algorithm)));
            System.out.println(format("\tScore: {0}", output.score));
            output.parameters.ifPresent(stringObjectMap -> System.out.println(format("\tParameters: {0}", stringObjectMap)));
            if (printSeparator) {
                System.out.println("\t------------------");
            }
        }

        ScoreTracker_Element getBestOutput() {
            return outputs.get(outputs.size() - 1);
        }

    }

    public static final class ScoreTracker_Element<U extends OutputModel> {

        final long score;
        final U output;
        final Class<? extends Algorithm> algorithm;
        final Optional<Map<String, Object>> parameters;

        public ScoreTracker_Element(
                final long score,
                final U output,
                final Class<? extends Algorithm> algorithm,
                final Map<String, Object> parameters) {
            this.score = score;
            this.output = output;
            this.algorithm = algorithm;
            this.parameters = Optional.ofNullable(parameters);
        }

        long getScore() {
            return score;
        }

    }

    /**************************************************************************************************************
     ***   Classes for parameter permutation streaming                                                          ***
     **************************************************************************************************************/

    public static final class ParameterStreamer {

        final List<ParameterState<? extends AlgorithmParameter>> parameterStates;
        final long numberOfScenarios;
        long scenarioIndex;

        ParameterStreamer(final List<AlgorithmParameter> parameters) {
            this.parameterStates = parameters.stream()
                    .map(this::toParameterState)
                    .collect(Collectors.toList());
            this.numberOfScenarios = getNumberOfScenarios(parameters);
            this.scenarioIndex = 0;
        }

        boolean hasNext() {
            return scenarioIndex < numberOfScenarios;
        }

        Map<String, Object> next() {
            if (scenarioIndex == 0) {
                initState();
            } else {
                nextState();
            }
            scenarioIndex++;
            return getParameters();
        }

        private long getNumberOfScenarios(final List<AlgorithmParameter> parameters) {
            return parameters.stream()
                    .mapToLong(AlgorithmParameter::getNumberOfScenarios)
                    .reduce(1, Math::multiplyExact);
        }

        private void initState() {
            parameterStates.forEach(ParameterState::reset);
        }

        private void nextState() {
            final ParameterState<?> parameterState = parameterStates.stream()
                    .filter(ParameterState::hasNext)
                    .findFirst().orElseThrow(() -> new RuntimeException("Could not find a parameter state with flag hasNext == true"));
            final int parameterStateIndex = parameterStates.indexOf(parameterState);
            parameterState.next();
            IntStream.range(0, parameterStateIndex)
                    .forEach(index -> parameterStates.get(index).reset());
        }

        private Map<String, Object> getParameters() {
            return parameterStates.stream()
                    .collect(Collectors.toMap(
                            parameterState -> parameterState.parameter.getName(),
                            ParameterState::current));
        }

        private ParameterState<? extends AlgorithmParameter> toParameterState(final AlgorithmParameter parameter) {
            if (parameter instanceof BoundedParameter) {
                return new ParameterState_Bounded((BoundedParameter) parameter);
            }
            if (parameter instanceof EnumeratedParameter) {
                return new ParameterState_Enumerated((EnumeratedParameter) parameter);
            }
            throw new RuntimeException(format("Unsupported algorithm parameter type: ''{}''", simpleName(parameter)));
        }

    }

    public static abstract class ParameterState<V extends AlgorithmParameter>  {

        final V parameter;

        abstract void reset();
        abstract boolean hasNext();
        abstract void next();
        abstract Object current();

        public ParameterState(final V parameter) {
            this.parameter = parameter;
        }

    }

    public static final class ParameterState_Bounded extends ParameterState<BoundedParameter>  {

        Long value;

        public ParameterState_Bounded(final BoundedParameter parameter) {
            super(parameter);
        }

        @Override
        void reset() {
            this.value = parameter.getLowerLimit();
        }

        @Override
        boolean hasNext() {
            return value + parameter.getStepSize() <= parameter.getUpperLimit();
        }

        @Override
        void next() {
            value += parameter.getStepSize();
        }

        @Override
        Number current() {
            return value;
        }

    }

    public static final class ParameterState_Enumerated extends ParameterState<EnumeratedParameter> {

        Integer index;

        public ParameterState_Enumerated(final EnumeratedParameter parameter) {
            super(parameter);
        }

        @Override
        void reset() {
            this.index = 0;
        }

        @Override
        boolean hasNext() {
            return index + 1 < parameter.getNumberOfScenarios();
        }

        @Override
        void next() {
            ++index;
        }

        @Override
        Object current() {
            return parameter.getValues().get(index);
        }
    }

    /**************************************************************************************************************
     ***   Definition of Builder (chaining) classes to enforce a method order                                   ***
     **************************************************************************************************************/

    public static final class ConfigBuilder<T extends InputModel, U extends OutputModel> {

        boolean debugMode;
        InputSpecifier inputSpecifier;
        List<String> inputFileNames;
        Class<? extends InputParser> inputParserClass;
        List<AlgorithmSpecification<T, U>> algorithmSpecifications;
        Class<? extends OutputValidator> outputValidatorClass;
        Class<? extends ScoreCalculator> scoreCalculatorClass;
        Class<? extends OutputProducer> outputProducerClass;
        Path inputFolder;
        Path outputFolder;
        int numberOfSuboptimalSolutionsToShow;

        private ConfigBuilder() {}

        public Configurator_InputSpecifier<T, U> configurator() {
            return new Configurator_InputSpecifier<>(this);
        }

    }

    public static final class Configurator_InputSpecifier<T extends  InputModel, U extends OutputModel> {

        final ConfigBuilder<T, U> configBuilder;

        Configurator_InputSpecifier(final ConfigBuilder<T, U> configBuilder) {
            this.configBuilder = configBuilder;
        }

        /**
         * Run your algorithm(s) on all the available input files.
         *
         * By default, input files are scanned in the src/main/resource/input folder, but this can be customized with
         *  the optional withCustomInputFolder(String inputFolderPath) builder method.
         */
        public Configurator_InputParser<T, U> forAllInputFiles() {
            configBuilder.inputSpecifier = ALL_INPUT_FILES;
            configBuilder.inputFileNames = new ArrayList<>();
            return new Configurator_InputParser<>(this);
        }

        /**
         * Run your algorithm(s) on a single input file.
         *
         * By default, input files are scanned in the src/main/resource/input folder, but this can be customized with
         *  the optional withCustomInputFolder(String inputFolderPath) builder method.
         *
         * Note: This builder method supports smart file name arguments. e.g. if you input file is called
         *  "example_input.in", you can simply pass the "example" string to this method (note that if this string
         *  occurs more than once, all matching input files are selected).
         *
         *  @param inputFileName the (smart) input file name
         */
        public Configurator_InputParser<T, U> forASingleInputFile(final String inputFileName) {
            configBuilder.inputSpecifier = SELECTED_INPUT_FILES;
            configBuilder.inputFileNames = List.of(inputFileName);
            return new Configurator_InputParser<>(this);
        }

        /**
         * Run your algorithm(s) on selected input files.
         *
         * By default, input files are scanned in the src/main/resource/input folder, but this can be customized with
         *  the optional withCustomInputFolder(String inputFolderPath) builder method.
         *
         * Note: This builder method supports smart file name arguments. e.g. if you input file is called
         *  "example_input.in", you can simply pass the "example" string to this method (note that if this string
         *  occurs more than once, all matching input files are selected).
         *
         *  @param inputFileName the (smart) input file name
         *  @param additionalInputFileNames additional (smart) input file names
         */
        public Configurator_InputParser<T, U> forSelectedInputFiles(final String inputFileName, final String... additionalInputFileNames) {
            configBuilder.inputSpecifier = SELECTED_INPUT_FILES;
            configBuilder.inputFileNames = join(inputFileName, additionalInputFileNames);
            return new Configurator_InputParser<>(this);
        }

    }

    public static final class Configurator_InputParser<T extends InputModel, U extends OutputModel> {

        final ConfigBuilder<T, U> configBuilder;

        Configurator_InputParser(final Configurator_InputSpecifier<T, U> configurator) {
            this.configBuilder = configurator.configBuilder;
        }

        /**
         * The component that will transform the file input to the specified POJO for easier processing.
         *
         * @param inputParserClass class reference of the InputParser implementation
         */
        public Configurator_Algorithm<T, U> withInputParser(final Class<? extends InputParser> inputParserClass) {
            configBuilder.inputParserClass = inputParserClass;
            return new Configurator_Algorithm<>(this);
        }

    }

    public static final class Configurator_Algorithm<T extends InputModel, U extends OutputModel> {

        final ConfigBuilder<T, U> configBuilder;

        Configurator_Algorithm(final Configurator_InputParser<T, U> configurator) {
            this.configBuilder = configurator.configBuilder;
        }

        /**
         * The algorithms you want to run the input on.
         *
         * Algorithms are specified using the AlgorithmSpecification.builder() builder. Simply follow the builder chain.
         *
         * @param algorithmSpecifications algorithm specification for the facilitator
         * @param additionalAlgorithmSpecifications additional algorithm specifications for the facilitator
         */
        public Configurator_OutputValidator<T, U> withAlgorithms(final AlgorithmSpecification algorithmSpecifications, final AlgorithmSpecification... additionalAlgorithmSpecifications) {
            configBuilder.algorithmSpecifications = join(algorithmSpecifications, additionalAlgorithmSpecifications);
            return new Configurator_OutputValidator<>(this);
        }

    }

    public static final class Configurator_OutputValidator<T extends InputModel, U extends OutputModel> {

        final ConfigBuilder<T, U> configBuilder;

        Configurator_OutputValidator(final Configurator_Algorithm<T, U> configurator) {
            this.configBuilder = configurator.configBuilder;
        }

        /**
         * Signals the absence of any kind of output validation.
         */
        public Configurator_ScoreCalculator<T, U> withoutOutputValidation() {
            return new Configurator_ScoreCalculator<>(this);
        }

        /**
         * The validator that will validate the algorithm output.
         *
         * In case the output is invalid, an exception will be thrown and the entire run (for all input files)
         *  considered invalid.
         *
         * @param outputValidatorClass class reference of the OutputValidator implementation
         */
        public Configurator_ScoreCalculator<T, U> withOutputValidator(final Class<? extends OutputValidator> outputValidatorClass) {
            configBuilder.outputValidatorClass = outputValidatorClass;
            return new Configurator_ScoreCalculator<>(this);
        }

    }

    public static final class Configurator_ScoreCalculator<T extends InputModel, U extends OutputModel> {

        final ConfigBuilder<T, U> configBuilder;

        Configurator_ScoreCalculator(final Configurator_OutputValidator<T, U> configurator) {
            this.configBuilder = configurator.configBuilder;
        }

        /**
         * The calculator that calculates the score for every algorithm output.
         *
         * This calculator is used to select the best possible solution for every algorithm.
         *
         * @param scoreCalculatorClass class reference of the ScoreCalculator implementation
         */
        public Configurator_OutputProducer<T, U> withScoreCalculator(final Class<? extends ScoreCalculator> scoreCalculatorClass) {
            configBuilder.scoreCalculatorClass = scoreCalculatorClass;
            return new Configurator_OutputProducer<>(this);
        }

    }

    public static final class Configurator_OutputProducer<T extends InputModel, U extends OutputModel> {

        final ConfigBuilder<T, U> configBuilder;

        Configurator_OutputProducer(final Configurator_ScoreCalculator<T, U> configurator) {
            this.configBuilder = configurator.configBuilder;
        }

        /**
         * The output producer that converts the algorithm output to the suitable output for a submission.
         *
         * @param outputProducerClass class reference of the OutputProducer implementation
         */
        public Configurator_Final<T, U> withOutputProducer(final Class<? extends OutputProducer> outputProducerClass) {
            configBuilder.outputProducerClass = outputProducerClass;
            return new Configurator_Final<>(this);
        }

    }

    public static final class Configurator_Final<T extends InputModel, U extends OutputModel> {

        final ConfigBuilder<T, U> configBuilder;

        Configurator_Final(final Configurator_OutputProducer<T, U> configBuilder) {
            this.configBuilder = configBuilder.configBuilder;
        }

        /**
         * Designates a custom input folder where the facilitator will look for input files.
         *
         * @param inputFolderPath the file path to the input folder
         */
        public Configurator_Final<T, U> withCustomInputFolder(final String inputFolderPath) {
            configBuilder.inputFolder = getFolder(inputFolderPath);
            return this;
        }

        /**
         * Designated a custom output folder where the generated output and sources zip will be written to.
         *
         * @param outputFolderPath the file path to the output folder
         */
        public Configurator_Final<T, U> withCustomOutputFolder(final String outputFolderPath) {
            configBuilder.outputFolder = getFolder(outputFolderPath);
            return this;
        }

        /**
         * Signals debug mode - where most facilitator logging will be disabled.
         *
         * Since the progress bar can be a nuisance while debugging an algorithm, this method enables you to disable
         *  facilitator logging such as the progess bar.
         */
        public Configurator_Final<T, U> debugMode() {
            configBuilder.debugMode = true;
            return this;
        }

        /**
         * Signals that we don't only want to know the optimal solutions, but the #DEFAULT_NUMBER_OF_SUBOPTIMAL_SOLUTIONS_TO_SHOW
         *  best solutions per input file.
         */
        public Configurator_Final<T, U> showSuboptimalSolutions() {
            return showSuboptimalSolutions(DEFAULT_NUMBER_OF_SUBOPTIMAL_SOLUTIONS_TO_SHOW);
        }

        /**
         * Signals that we don't only want to know the optimal solutions, but the #numberOfSuboptimalSolutionsToShow
         *  best solutions per input file.
         */
        public Configurator_Final<T, U> showSuboptimalSolutions(final int numberOfSuboptimalSolutionsToShow) {
            configBuilder.numberOfSuboptimalSolutionsToShow = numberOfSuboptimalSolutionsToShow;
            return this;
        }

        public void run() {
            new HashCodeFacilitator<>(configBuilder).run();
        }

    }

}
