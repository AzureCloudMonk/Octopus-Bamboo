package com.octopus.bamboo.plugins.task.push;

import com.atlassian.bamboo.build.logger.LogMutator;
import com.atlassian.bamboo.process.ExternalProcessBuilder;
import com.atlassian.bamboo.process.ProcessService;
import com.atlassian.bamboo.task.*;
import com.atlassian.bamboo.v2.build.agent.capability.CapabilityContext;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.utils.process.ExternalProcess;
import com.google.common.base.Splitter;
import com.octopus.constants.OctoConstants;
import com.octopus.services.CommonTaskService;
import com.octopus.services.FileService;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.Predicate;
import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.tools.ant.types.Commandline;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * The Bamboo Task that is used to push artifacts to Octopus Deploy
 */
@Component
@ExportAsService({PushTask.class})
@Named("pushTask")
public class PushTask extends AbstractTaskConfigurator implements CommonTaskType {
    private static final Logger LOGGER = LoggerFactory.getLogger(PushTask.class);
    private final CommonTaskService commonTaskService;
    private final FileService fileService;
    private final LogMutator logMutator;
    @ComponentImport
    private ProcessService processService;
    @ComponentImport
    private CapabilityContext capabilityContext;

    /**
     * Constructor. Params are injected by Spring under normal usage.
     *
     * @param processService    The service used to run external executables
     * @param capabilityContext The service holding Bamboo's capabilities
     * @param commonTaskService The service used for common task operations
     * @param logMutator The service used to mask api keys
     */
    @Inject
    public PushTask(@NotNull final ProcessService processService,
                    @NotNull final CapabilityContext capabilityContext,
                    @NotNull final CommonTaskService commonTaskService,
                    @NotNull final FileService fileService,
                    @NotNull final LogMutator logMutator) {
        checkNotNull(processService, "processService cannot be null");
        checkNotNull(capabilityContext, "capabilityContext cannot be null");
        checkNotNull(commonTaskService, "commonTaskService cannot be null");
        checkNotNull(fileService, "fileService cannot be null");
        checkNotNull(logMutator, "logMutator cannot be null");

        this.processService = processService;
        this.capabilityContext = capabilityContext;
        this.commonTaskService = commonTaskService;
        this.fileService = fileService;
        this.logMutator = logMutator;
    }

    public ProcessService getProcessService() {
        return processService;
    }

    public void setProcessService(final ProcessService processService) {
        this.processService = processService;
    }

    public CapabilityContext getCapabilityContext() {
        return capabilityContext;
    }

    public void setCapabilityContext(final CapabilityContext capabilityContext) {
        this.capabilityContext = capabilityContext;
    }

    @NotNull
    public TaskResult execute(@NotNull final CommonTaskContext taskContext) throws TaskException {
        checkNotNull(taskContext, "taskContext cannot be null");

        final String octopusCli = taskContext.getConfigurationMap().get(OctoConstants.OCTOPUS_CLI);
        final String serverUrl = taskContext.getConfigurationMap().get(OctoConstants.SERVER_URL);
        final String apiKey = taskContext.getConfigurationMap().get(OctoConstants.API_KEY);
        final String patterns = taskContext.getConfigurationMap().get(OctoConstants.PUSH_PATTERN);
        final String forceUpload = taskContext.getConfigurationMap().get(OctoConstants.FORCE);
        final Boolean forceUploadBoolean = BooleanUtils.isTrue(BooleanUtils.toBooleanObject(forceUpload));
        final String loggingLevel = taskContext.getConfigurationMap().get(OctoConstants.VERBOSE_LOGGING);
        final Boolean verboseLogging = BooleanUtils.isTrue(BooleanUtils.toBooleanObject(loggingLevel));
        final String additionalArgs = taskContext.getConfigurationMap().get(OctoConstants.ADDITIONAL_COMMAND_LINE_ARGS_NAME);

        checkState(StringUtils.isNotBlank(octopusCli), "OCTOPUS-BAMBOO-INPUT-ERROR-0002: Octopus CLI can not be blank");
        checkState(StringUtils.isNotBlank(serverUrl), "OCTOPUS-BAMBOO-INPUT-ERROR-0002: Octopus URL can not be blank");
        checkState(StringUtils.isNotBlank(apiKey), "OCTOPUS-BAMBOO-INPUT-ERROR-0002: API key can not be blank");
        checkState(StringUtils.isNotBlank(patterns), "OCTOPUS-BAMBOO-INPUT-ERROR-0002: Package paths can not be blank");

        taskContext.getBuildLogger().getMutatorStack().add(logMutator);

         /*
            Get the list of matching files that need to be uploaded
         */
        final List<File> files = new ArrayList<>();

        final Iterable<String> patternSplit = Splitter.on("\n")
                .trimResults()
                .omitEmptyStrings()
                .split(patterns);
        for (final String pattern : patternSplit) {
            final List<File> matchingFiles = fileService.getMatchingFile(taskContext.getWorkingDirectory(), pattern);
            /*
                Don't add duplicates
             */
            for (final File file : matchingFiles) {
                if (file != null) {
                    final File existing = CollectionUtils.find(files, new Predicate<File>() {
                        @Override
                        public boolean evaluate(final File existingFile) {
                            return existingFile.getAbsolutePath().equals(file.getAbsolutePath());
                        }
                    });

                    if (existing == null) {
                        files.add(file);
                    }
                }
            }
        }

        /*
            Fail if no files were matched
         */
        if (files.isEmpty()) {
            commonTaskService.logError(taskContext, "OCTOPUS-BAMBOO-INPUT-ERROR-0001: The pattern \n"
                    + patterns
                    + "\n failed to match any files");
            return commonTaskService.buildResult(taskContext, false);
        }

        /*
            Build up the commands to be passed to the octopus cli
         */
        final List<String> commands = new ArrayList<String>();

        commands.add(OctoConstants.PUSH_COMMAND);

        commands.add("--server");
        commands.add(serverUrl);

        commands.add("--apiKey");
        commands.add(apiKey);

        if (forceUploadBoolean) {
            commands.add("--replace-existing");
        }

        if (verboseLogging) {
            commands.add("--debug");
        }

        if (StringUtils.isNotBlank(additionalArgs)) {
            final String[] myArgs = Commandline.translateCommandline(additionalArgs);
            commands.addAll(Arrays.asList(myArgs));
        }

        for (final File file : files) {
            try {
                commands.add("--package");
                commands.add(file.getCanonicalPath());
            } catch (final IOException ex) {
                commonTaskService.logError(
                        taskContext,
                        "An exception was thrown while processing the file " + file.getAbsolutePath());
                return TaskResultBuilder.newBuilder(taskContext).failed().build();
            }
        }

        final String cliPath = capabilityContext.getCapabilityValue(
                OctoConstants.OCTOPUS_CLI_CAPABILITY + "." + octopusCli);

        if (StringUtils.isNotBlank(cliPath) && new File(cliPath).exists()) {
            commands.add(0, cliPath);

            final ExternalProcess process = processService.executeExternalProcess(taskContext,
                    new ExternalProcessBuilder()
                            .command(commands)
                            .workingDirectory(taskContext.getWorkingDirectory()));

            return TaskResultBuilder.newBuilder(taskContext)
                    .checkReturnCode(process, 0)
                    .build();
        }

        commonTaskService.logError(
                taskContext,
                "OCTOPUS-BAMBOO-INPUT-ERROR-0003: The path of \"" + cliPath + "\" for the selected Octopus CLI does not exist.");
        return TaskResultBuilder.newBuilder(taskContext).failed().build();
    }
}
