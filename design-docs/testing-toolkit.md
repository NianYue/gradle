# Testing custom build logic

This stories are partially extracted, re-aligned and distilled from this [spec](https://github.com/gradle/gradle/blob/master/design-docs/testing-user-build-logic.md).

## High-level goals

* Writing and executing integration tests with `Project` instance that behaves similar to real-world object (aka project created by `ProjectBuilder`).
* Writing and executing functional tests against build scripts via Tooling API.
* Providing utility methods for common operations in tests e.g. creating directories/files.
* Generating Ivy and Maven repositories for testing purposes. Creating modules and artifacts in a repository to emulate dependency management behavior.

The test-kit will be agnostic of the test framework preferred by the user (e.g. JUnit, TestNG, Spock). Adapters will be provided to make it easy to integrate the test-kit with a specific test framework.

## Technical details

* Except for the Spock test adapter all code will be developed in Java.
* The test-kit and all test adapters are implemented in a separate repository to Gradle core. The project is modeled as multi-project build. The repository is hosted on `https://github.com/gradle/testkit`.
* The artifacts for test-kit and test adapters are published to a central repository (likely our own repository). Publishing Gradle core should trigger publishing the test-kit/test adapters.
* The build of the project will depends on the latest Gradle version. The version will need to updated manually in the beginning. We could also think of an automated solution here that uses the latest
nightly.

## Story 1: User creates and executes a functional test using the test-kit

A set of interfaces/builders will be developed to provide programmatic execution of Gradle builds. Tests are executed with the Tooling API. The published version of the artifact will be compatible to
with a specific Gradle version e.g. Gradle 2.4 with the test-kit version 2.4.

### User visible changes

A user will need to declare the dependency on the test-kit in the build script. The test-kit does not have an opinion about the used test framework. It's up to the user to choose a test framework
and declare its dependency.

    dependencies {
        testCompile 'org.gradle.testkit:testkit:2.4'

        // Declare the preferred test framework
        testCompile '...'
    }

As a user, you write your functional test by using the following interfaces:

    package org.gradle.testkit.functional;

    public interface GradleRunner {
        File getWorkingDir();
        void setWorkingDir(File directory);

        List<String> getArguments();
        void setArguments(List<String> string);

        void useTasks(List<String> taskNames);

        BuildResult succeeds();
        BuildResult fails();
    }

    public interface BuildResult {
        String getStandardOutput();
        String getStandardError();
        List<String> getExecutedTasks();
        Set<String> getSkippedTasks();
    }

    public class GradleRunnerFactory {
        public static GradleRunner create() { /* ... */ }
    }

A functional test using Spock could look as such:

    class UserFunctionalTest extends Specification {
        def "run build"() {
            given:
            def dir = new File("/tmp/gradle-build")

            new File(dir, "build.gradle").text << """
                task helloWorld {
                    doLast {
                        println 'Hello world!'
                    }
                }
            """

            when:
            def result = GradleRunnerFactory.create().with {
                workingDir = dir
                arguments << "helloWorld"
                succeed()
            }

            then:
            result.standardOutput.contains "Hello World!"
        }
    }

### Implementation

* The implementation will be backed by the Tooling API.
* The Gradle version/distribution selected will be what is selected by the Tooling APIs default behaviour (i.e. at this point, this is not specifyable).
* No environmental control will be allowed (e.g. setting env vars or sys props).

### Test coverage

* A build can be run successfully.
* A failed build can be run successfully, that is a build that ultimate fails (through either an unexpected Gradle error or a legitimate failure such as a test failure) can be run without an exception
being thrown by the runner (later stories add more options on how to respond to build failures).
* When a build fails unexpectedly, good diagnostic messages are produced.
* When a build succeeds unexpectedly, good diagnostic messages are produced.
* Tooling API mechanical failures produce good diagnostic messages.

### Open issues

* The Tooling API executes the test in a Daemon JVM. Debugging in the IDE won't work until we allow for executing the tests in the same JVM process.
* The daemon is known to still have issues. What should happen if one of the daemon crashes or misbehaves?
* If the artifacts are published to a binary repository hosted by Gradle, users will have to declare the repository. Are we OK with that as the initial approach?
* Setting up a multi-project build should be easy. At the moment a user would have to do all the leg work. In a later story the work required could be simplified by introducing helper methods.

## Story 2: JUnit test adapter

* Write a JUnit test rule for creating temporary directories for test execution per test case.

### User visible changes

A user will need to declare the dependency on the test adapter and JUnit in the build script.

    dependencies {
        testCompile 'org.gradle.testkit:testkit-junit-adapter:2.4'
        testCompile 'junit:junit:4.8.2'
    }

As a user, you write your functional test by extending the base class.

    import org.gradle.testkit.functional.junit.FunctionalTest;

    import java.util.List;
    import java.util.ArrayList;
    import org.junit.Test;
    import static org.junit.Assert.assertTrue;

    public class UserFunctionalTest extends FunctionalTest {
        @Test
        public void canExecuteBuildFileUsingTheJavaPlugin() {
            writeToFile(getBuildFile(), "apply plugin: 'java'");
            BuildResult result = succeeds("build")
            List<String> expectedTaskNames = new ArrayList<String>();
            expectedTaskNames.add("classes");
            expectedTaskNames.add("test");
            expectedTaskNames.add("check");
            assertTrue(result.getExecutedTasks().containsAll(expectedTaskNames));
        }
    }

### Implementation

* Write a JUnit test rule for creating temporary directories for test execution per test case.
* The functional test implementation uses the `GradleRunner` and provides methods to simplify the creation of tests with JUnit.
* The metadata of the published library declares a dependency on the test-kit, the JUnit test adapter but not the test framework.

The base class implementation could look similar the following code snippet:

    package org.gradle.testkit.functional.junit;

    import org.gradle.testkit.functional.*;
    import org.gradle.testkit.functional.junit.TestNameTestDirectoryProvider;

    import org.junit.Before;
    import org.junit.Rule;

    public abstract class FunctionalTest {
        @Rule private final TestNameTestDirectoryProvider temporaryFolder = new TestNameTestDirectoryProvider();
        private final GradleRunner gradleRunner = GradleRunnerFactory.create();

        @Before
        public void setup() {
            gradleRunner.setWorkingDir(testDirectoryProvider.getTestDirectory());
        }

        protected GradleRunner getGradleRunner() {
            return gradleRunner;
        }

        protected File getTestDirectory() {
            return temporaryFolder.getTestDirectory();
        }

        protected File getBuildFile() {
            return testDirectory.file("build.gradle");
        }

        protected File getSettingsFile() {
            return testDirectory.file("settings.gradle");
        }

        protected BuildResult succeeds(String... tasks) {
            return gradleRunner.useTasks(Arrays.asList(tasks)).succeeds();
        }

        protected BuildResult fails(String... tasks) {
            return gradleRunner.useTasks(Arrays.asList(tasks)).fails();
        }

        protected void writeToFile(File file, String text) {
            ...
        }
    }

### Test coverage

* The test adapter dependency can be resolved from a central repository. The test-kit library is resolved as transitive dependency.
* Users can use `FunctionalTest` to write their own functional tests and successfully execute them.
* Users can create new files and directories with the JUnit Rule accessible from the base class.
* Users can create an assertion on whether tasks should be executed successfully and whether the execution should fail.
* Each test method creates a new temporary directory. This temporary test directory is not deleted after test execution.
* A user can create temporary files and directories and files with the provided test rule.
* A user can configure the `GradleRunner` e.g. to add arguments.
* Test methods can write a `build.gradle` and `settings.gradle` file.

### Open issues

-

## Story 3: Spock test adapter

Using the Spock framework for testing is a common scenario in modern software projects. This test adapter will provide a library for using the test-kit with Spock tests. The test adapter will
depend on the JUnit test adapter so it can share certain JUnit-specific classes like a test directory provider.

### User visible changes

A user will need to declare the dependency on the test adapter and JUnit.

    dependencies {
        testCompile 'org.gradle.testkit:testkit-spock-adapter:2.4'
        testCompile 'org.spockframework:spock-core:1.0-groovy-2.4'
    }

As a user, you write your functional test by extending the base class.

     import org.gradle.testkit.functional.spock.FunctionalTest;

    class UserFunctionalTest extends FunctionalTest {
        def "can execute build file using the Java plugin"() {
            given:
            buildFile << "apply plugin: 'java'"

            when:
            def result = succeeds('build')

            then:
            result.executedTasks.containsAll(['classes', 'test', 'check']);
        }
    }

### Implementation

* The functional test implementation uses the `GradleRunner` and provides methods to simplify the creation of tests with Spock.
* The metadata of the published library declares a dependency on the test-kit but not the test framework.

The implementation of the Spock test adapter could look similar to this base class. The class extends `spock.lang.Specification`.

    package org.gradle.testkit.functional.spock;

    import org.gradle.testkit.functional.*;
    import org.gradle.testkit.functional.junit.TestNameTestDirectoryProvider;

    import org.junit.Rule;

    public abstract class FunctionalTest extends Specification {
        @Rule private final TestNameTestDirectoryProvider temporaryFolder = new TestNameTestDirectoryProvider();
        private final GradleRunner gradleRunner = GradleRunnerFactory.create();

        def setup() {
            gradleRunner.setWorkingDir(testDirectoryProvider.getTestDirectory());
        }

        protected GradleRunner getGradleRunner() {
            return gradleRunner;
        }

        protected File getTestDirectory() {
            return temporaryFolder.getTestDirectory();
        }

        protected File getBuildFile() {
            return testDirectory.file("build.gradle");
        }

        protected File getSettingsFile() {
            return testDirectory.file("settings.gradle");
        }

        protected BuildResult succeeds(String... tasks) {
            return gradleRunner.useTasks(Arrays.asList(tasks)).succeeds();
        }

        protected BuildResult fails(String... tasks) {
            return gradleRunner.useTasks(Arrays.asList(tasks)).fails();
        }
    }

### Test coverage

* The test adapter dependency can be resolved from a central repository. The test-kit library is resolved as transitive dependency.
* Users can use `FunctionalTest` to write their own functional tests and successfully execute them.
* Users can create new files and directories with the JUnit Rule accessible from the base class.
* Users can create an assertion on whether tasks should be executed successfully and whether the execution should fail.
* Each test method creates a new temporary directory. This temporary test directory is not deleted after test execution.
* A user can create temporary files and directories and files with the provided test rule.
* A user can configure the `GradleRunner` e.g. to add arguments.
* Test methods can write a `build.gradle` and `settings.gradle` file.

### Open issues

-

## Story 4: User creates and executes an integration test using the test-kit

A set of interfaces/builders will be developed to provide programmatic creation of a dummy `Project` instance.

### User visible changes

As a user, you write your integration test by using the following interfaces:

    package org.gradle.testkit.integration;

    public interface GradleProjectBuilder {
        File getWorkingDir();
        void setWorkingDir(File directory);

        String getName();
        void setName(String projectName);

        Project getParent();
        void setParent(Project parentProject);

        Project build();
    }

    public class GradleProjectBuilderFactory {
        public static GradleProjectBuilder create() { /* ... */ }
    }

A integration test using Spock could look as such:

    class UserIntegrationTest extends Specification {
        def "run build"() {
            given:
            def project = GradleProjectBuilderFactory.create().with {
                workingDir = new File("/tmp/gradle-build")
                build()
            }

            when:
            project.plugins.apply('java')

            then:
            project.tasks.getByName('classes')
            project.tasks.getByName('test')
            project.tasks.getByName('check')
        }
    }

### Implementation

* The actual implementation of the dummy project creation is hidden from the user. As a start we can reuse the `ProjectBuilder`. Later this implementation can be swapped out.
* Provide base classes for all test adapters.
* The Tooling API is not involved.

### Test coverage

* A project can be created.
* Projects can form project hierarchies to model multi-project builds.
* Methods on a project instance can be called as if it would be a regular project instance. There are some limitation though.

### Open issues

* A user should be allowed to execute a task with a public method. Calling `Task.execute()` is sufficient but is an internal API. It's easier to test custom task types.
* A user should be allowed to evaluate a project to trigger lifecycle events. Calling `Project.evaluate()` works but is an internal API.
* Where do we draw the line between dummy project and real project instance?

## Story 5: User can create repositories and populate dependencies

A set of interfaces will be developed to provide programmatic creation of a repositories and published dependencies. A benefit of this approach is that a test setup doesn't need to reach out to the
internet for interacting with the dependency management mechanics. Furthermore, the user can create artifacts and metadata for test scenarios modeling the specific use case.

### User visible changes

A user will need to declare the dependency on the test fixtures:

    dependencies {
        testCompile 'org.gradle.testkit:test-fixtures:2.4'
    }

As a user, you interact with the following interfaces to create repositories and dependencies:

    package org.gradle.testkit.fixtures;

    public interface Repository {
        URI getUri();
        Module module(String group, String module);
        Module module(String group, String module, Object version);
    }

    public interface Module<T extends Module> {
        T publish();
    }

    package org.gradle.testkit.fixtures.maven;

    public interface MavenRepository extends Repository {
        MavenModule module(String groupId, String artifactId);
        MavenModule module(String groupId, String artifactId, String version);
    }

    public interface MavenModule extends Module {
        MavenModule publish();
        MavenModule dependsOn(MavenModule module);

        // getter methods for artifacts and coordinates
    }

    package org.gradle.testkit.fixtures.ivy;

    public interface IvyRepository extends Repository {
        IvyModule module(String organisation, String module);
        IvyModule module(String organisation, String module, String revision);
    }

    public interface IvyModule extends Module {
        IvyModule publish();
        IvyModule dependsOn(String organisation, String module, String revision);

        // getter methods for artifacts and coordinates
    }

The use of the test fixture in an integration test could look as such:

    class UserFunctionalTest extends FunctionalTest {
        MavenRepository mavenRepository
        MavenModule mavenModule

        def setup() {
            MavenRepository mavenRepository = new MavenFileRepository(new File('/tmp/gradle-build'))
            MavenModule mavenModule = mavenRepository.module('org.gradle', 'test', '1.0')
            mavenModule.publish()
        }

         def "can resolve dependency"() {
            given:
            buildFile << ""
            configurations {
                myConf
            }

            dependencies {
                myConf "${mavenModule.groupId}:${mavenModule.artifactId}:${mavenModule.version}"
            }

            repositories {
                maven {
                    url mavenRepository.uri.toURL()
                }
            }
            """

            when:
            def result = succeeds('dependencies')

            then:
            result.standardOutput.contains("""
            myConf
            \--- ${mavenModule.groupId}:${mavenModule.artifactId}:${mavenModule.version}
            """)
        }
    }

### Implementation

* The support for test fixtures becomes its own project in the multi-project build hierarchy of the test-kit.
* The artifact for test fixtures will be published separately from the test-kit and test adapters.
* Default implementations for `IvyRepository`, `MavenRepository`, `IvyModule` and `MavenModule` will be file-based.
* Modules can depend on each other to model transitive dependency relations.

### Test coverage

* User can create an Ivy repository representation in a directory of choice.
* User can create a Maven repository representation in a directory of choice.
* The Maven repository instance allows for creating modules in the repository with the standard Maven structure.
* The Ivy repository instance allows for creating modules in the repository with the default Ivy structure.
* Module dependencies can be modeled. On resolving the top-level dependency transitive dependencies are resolved as well.

### Open issues

-
