package org.jboss.windup.rules.tests;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.forge.arquillian.AddonDependencies;
import org.jboss.forge.arquillian.AddonDependency;
import org.jboss.forge.arquillian.archive.AddonArchive;
import org.jboss.forge.furnace.Furnace;
import org.jboss.forge.furnace.util.Predicate;
import org.jboss.forge.furnace.util.Visitor;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.windup.config.DefaultEvaluationContext;
import org.jboss.windup.config.GraphRewrite;
import org.jboss.windup.config.RuleProvider;
import org.jboss.windup.config.RuleSubset;
import org.jboss.windup.config.metadata.RuleMetadataType;
import org.jboss.windup.config.parser.ParserContext;
import org.jboss.windup.exec.WindupProcessor;
import org.jboss.windup.exec.configuration.WindupConfiguration;
import org.jboss.windup.graph.GraphContext;
import org.jboss.windup.graph.GraphContextFactory;
import org.jboss.windup.graph.model.ProjectModel;
import org.jboss.windup.graph.model.resource.FileModel;
import org.jboss.windup.rules.apps.java.config.SourceModeOption;
import org.jboss.windup.util.exception.WindupException;
import org.jboss.windup.util.file.FileSuffixPredicate;
import org.jboss.windup.util.file.FileVisit;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ocpsoft.logging.Logger;
import org.ocpsoft.rewrite.config.Configuration;
import org.ocpsoft.rewrite.config.ConfigurationBuilder;
import org.ocpsoft.rewrite.config.Rule;
import org.ocpsoft.rewrite.context.Context;
import org.ocpsoft.rewrite.param.DefaultParameterValueStore;
import org.ocpsoft.rewrite.param.ParameterValueStore;

/**
 * This test finds all *.windup.test.xml files in the current project and executes them.
 *
 * The execution of tests can be affected by System properties. Available properties include:
 *
 * <ul>
 * <li><b>runTestsMatching:</b> A regular expression specifying which tests to run. Eg, Foo.windup.test.xml will insure that only
 * "Foo.windup.test.xml" is executed by the test runner.</li>
 * </ul>
 *
 * @author jsightler
 *
 */
@RunWith(Arquillian.class)
public class WindupRulesTest
{
    private static Logger LOG = Logger.getLogger(WindupRulesTest.class);

    private static final String RUN_TEST_MATCHING = "runTestsMatching";
    private static final String RUN_TEST_ID_MATCHING = "runTestIdMatching";

    @Deployment
    @AddonDependencies({
                @AddonDependency(name = "org.jboss.windup.exec:windup-exec"),
                @AddonDependency(name = "org.jboss.windup.config:windup-config-xml"),
                @AddonDependency(name = "org.jboss.windup.rules.apps:windup-rules-java"),
                @AddonDependency(name = "org.jboss.windup.rules.apps:windup-rules-java-ee"),
                @AddonDependency(name = "org.jboss.windup.rules.apps:windup-rules-java-project"),
                @AddonDependency(name = "org.jboss.windup.rules.apps:windup-rules-xml"),
                @AddonDependency(name = "org.jboss.windup.reporting:windup-reporting"),
                @AddonDependency(name = "org.jboss.windup.utils:windup-utils"),
                @AddonDependency(name = "org.jboss.forge.furnace.container:cdi")
    })
    public static AddonArchive getDeployment()
    {
        return ShrinkWrap.create(AddonArchive.class)
                    .addBeansXML()
                    .addPackage(WindupRulesTest.class.getPackage());
    }

    @Inject
    private Furnace furnace;

    @Inject
    private GraphContextFactory factory;

    @Inject
    private WindupProcessor processor;

    private Pattern testToExecutePattern;

    @Test
    public void testWindupRules()
    {
        final List<String> successes = new ArrayList<>();
        final Map<String, Exception> errors = new LinkedHashMap<>();

        FileSuffixPredicate predicate = new FileSuffixPredicate("\\.windup\\.test\\.xml");
        final File directory = new File("rules");
        final File rulesReviewed = new File("rules-reviewed");
        Visitor<File> mainVisitor = new RuleTestVisitor(successes, errors, directory);
        Visitor<File> rulesReviewedVisitor = new RuleTestVisitor(successes, errors, rulesReviewed);

        FileVisit.visit(directory, predicate, mainVisitor);
        FileVisit.visit(rulesReviewed, predicate, rulesReviewedVisitor);

        System.out.println("Successful tests:\n");
        for (String successfulTest : successes)
        {
            System.out.println("\t" + successfulTest);
        }
        System.out.println();

        if (!errors.isEmpty())
        {
            StringBuilder result = new StringBuilder();
            for (Map.Entry<String, Exception> entry : errors.entrySet())
            {
                String message = getExceptionMessage(entry.getValue());
                result.append("Error with test: " + entry.getKey()).append("\n");
                result.append("\tCause: ").append(message).append("\n");
            }
            System.out.println("Failed tests:\n");
            System.out.println(result.toString());
            Assert.fail(result.toString());
        }
    }

    private class RuleTestVisitor implements Visitor<File>
    {
        final List<String> successes;
        final Map<String, Exception> errors;
        final File directory;

        public RuleTestVisitor(List<String> successes, Map<String, Exception> errors, File directory)
        {
            this.successes = successes;
            this.errors = errors;
            this.directory = directory;
        }

        @Override
        public void visit(File ruleTestFile)
        {
            final ParserContext parser = new ParserContext(furnace);
            if (!shouldExecuteTest(ruleTestFile))
            {
                return;
            }
            try
            {
                Path outputPath = getDefaultPath();
                try (GraphContext context = factory.create(outputPath))
                {
                    // load the ruletest file
                    RuleTest ruleTest = parser.processDocument(ruleTestFile.toURI());
                    List<Path> rulePaths = new ArrayList<>();
                    if (ruleTest.getRulePaths().isEmpty())
                    {
                        // The default path is ../, so this is two directories up from the test file iteself.
                        rulePaths.add(ruleTestFile.getParentFile().getParentFile().toPath().normalize().toAbsolutePath());
                    }
                    else
                    {
                        for (String rulePath : ruleTest.getRulePaths())
                        {
                            Path ruleTestDirectory = ruleTestFile.toPath().getParent().normalize();
                            Path path = ruleTestDirectory.resolve(rulePath).normalize().toAbsolutePath();
                            rulePaths.add(path.toAbsolutePath());
                        }
                    }

                    Configuration ruleTestConfiguration = parser.getBuilder().getConfiguration(context);

                    String idsToExecute = System.getProperty(RUN_TEST_ID_MATCHING);
                    if(idsToExecute != null) {
                        ConfigurationBuilder newConfiguration = ConfigurationBuilder.begin();
                        for (Rule rule : ruleTestConfiguration.getRules())
                        {
                            if(rule.getId().matches(idsToExecute)) {
                                newConfiguration.addRule(rule);
                            }
                        }
                        ruleTestConfiguration = newConfiguration;
                    }
                    for (Rule rule : ruleTestConfiguration.getRules())
                    {
                        parser.getBuilder().enhanceRuleMetadata(parser.getBuilder(), rule);
                        if (rule instanceof Context)
                        {
                            ((Context) rule).put(RuleMetadataType.HALT_ON_EXCEPTION, true);
                        }
                    }

                    // run windup
                    File testDataPath = new File(ruleTestFile.getParentFile(), ruleTest.getTestDataPath());
                    Path reportPath = outputPath.resolve("reports");
                    runWindup(context, directory, rulePaths, testDataPath, reportPath.toFile(),ruleTest.isSourceMode());

                    // run the assertions from the ruletest file
                    GraphRewrite event = new GraphRewrite(context);
                    RuleSubset.create(ruleTestConfiguration).perform(event, createEvalContext(event));
                }
                successes.add(ruleTestFile.toString());
            }
            catch (Exception e)
            {
                e.printStackTrace();
                errors.put(ruleTestFile.toString(), e);
            }
        }
    }

    private boolean shouldExecuteTest(File testFile)
    {
        String testToExecute = System.getProperty(RUN_TEST_MATCHING);
        if (StringUtils.isBlank(testToExecute))
            return true;

        if (testToExecutePattern == null)
            testToExecutePattern = Pattern.compile(testToExecute);

        Matcher m = testToExecutePattern.matcher(testFile.toString());
        if (!m.find())
        {
            LOG.info("Skipping test: " + testFile + " as it does not match pattern: " + testToExecute);
            return false;
        }
        else
        {
            LOG.info("Running test: " + testFile + " as it matches pattern: " + testToExecute);
            return true;
        }
    }

    private String getExceptionMessage(Exception e)
    {
        if (e instanceof WindupException)
        {
            WindupException windupException = (WindupException) e;
            if (windupException instanceof WindupAssertionException)
            {
                return windupException.getMessage();
            }

            Throwable cause = windupException.getCause();
            while (cause != null)
            {
                if (cause instanceof WindupAssertionException)
                    return cause.getMessage();
                cause = cause.getCause();
            }
        }

        return e.getMessage() == null ? "Unknown" : e.getMessage();
    }

    private DefaultEvaluationContext createEvalContext(GraphRewrite event)
    {
        final DefaultEvaluationContext evaluationContext = new DefaultEvaluationContext();
        final DefaultParameterValueStore values = new DefaultParameterValueStore();
        evaluationContext.put(ParameterValueStore.class, values);
        return evaluationContext;
    }

    private Path getDefaultPath()
    {
        return FileUtils.getTempDirectory().toPath().resolve("WindupRulesTests").resolve("windupgraph_" + RandomStringUtils.randomAlphanumeric(6));
    }

    private void runWindup(GraphContext context, File baseRuleDirectory, final List<Path> rulePaths, File input, File output, boolean sourceMode) throws IOException
    {
        ProjectModel pm = context.getFramed().addVertex(null, ProjectModel.class);
        pm.setName("Project: " + input.getAbsolutePath());
        FileModel inputPath = context.getFramed().addVertex(null, FileModel.class);
        inputPath.setFilePath(input.getCanonicalPath());

        FileUtils.deleteDirectory(output);
        Files.createDirectories(output.toPath());

        inputPath.setProjectModel(pm);
        pm.setRootFileModel(inputPath);
        WindupConfiguration windupConfiguration = new WindupConfiguration()
                    .setGraphContext(context);
        windupConfiguration.addInputPath(Paths.get(inputPath.getFilePath()));
        windupConfiguration.setOutputDirectory(output.toPath());
        windupConfiguration.addDefaultUserRulesDirectory(baseRuleDirectory.toPath());
        windupConfiguration.setOptionValue(SourceModeOption.NAME, sourceMode);

        final String baseRulesPathNormalized = baseRuleDirectory.toPath().normalize().toAbsolutePath().toString();
        windupConfiguration.setRuleProviderFilter(new Predicate<RuleProvider>()
        {
            @Override
            public boolean accept(RuleProvider type)
            {
                if (type.getMetadata().getOrigin() == null)
                    return true;

                if (!type.getMetadata().getOrigin().contains(baseRulesPathNormalized))
                    return true;

                for (Path acceptedRulePath : rulePaths)
                {
                    if (type.getMetadata().getOrigin().contains(acceptedRulePath.toString()))
                        return true;
                }
                return false;
            }
        });
        processor.execute(windupConfiguration);
    }
}
