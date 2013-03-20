/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.integtests.resolve.ivy
import org.gradle.integtests.fixtures.AbstractDependencyResolutionTest
import spock.lang.Unroll

class IvyDescriptorResolveIntegrationTest extends AbstractDependencyResolutionTest {
    def "substitutes system properties into ivy descriptor"() {
        given:
        ivyRepo.module("org.gradle", "test", "1.45")
                .dependsOn('org.gradle.${sys_prop}', 'module_${sys_prop}', 'v_${sys_prop}')
                .publish()

        ivyRepo.module("org.gradle.111", "module_111", "v_111").publish()

        and:
        buildFile << """
repositories { ivy { url "${ivyRepo.uri}" } }
configurations { compile }
dependencies {
    compile "org.gradle:test:1.45"
}

task check << {
    configurations.compile.resolvedConfiguration.firstLevelModuleDependencies.each {
        it.children.each { transitive ->
            assert transitive.moduleGroup == "org.gradle.111"
            assert transitive.moduleName == "module_111"
            assert transitive.moduleVersion == "v_111"
        }
    }
    assert configurations.compile.collect { it.name } == ['test-1.45.jar', 'module_111-v_111.jar']
}
"""

        when:
        executer.withArgument("-Dsys_prop=111")

        then:
        succeeds "check"
    }

    def "merges values from included descriptor file"() {
        given:
        final parentModule = ivyRepo.module("org.gradle.parent", "parent_module", "1.1").dependsOn("org.gradle.dep", "dep_module", "1.1").publish()
        ivyRepo.module("org.gradle.dep", "dep_module", "1.1").publish()

        final module = ivyRepo.module("org.gradle", "test", "1.45")
        final extendAttributes = ["organisation": "org.gradle.parent", "module": "parent_module", "revision": "1.1"]
        if (includeLocation) {
            extendAttributes["location"] = parentModule.ivyFile.absolutePath
        }
        module.withXml {
            asNode().info[0].appendNode("extends", extendAttributes)
        }
        module.publish()

        and:
        buildFile << """
repositories { ivy { url "${ivyRepo.uri}" } }
configurations { compile }
dependencies {
    compile "org.gradle:test:1.45"
}

task check << {
    assert configurations.compile.collect { it.name } == ['test-1.45.jar', 'dep_module-1.1.jar']
}
"""

        expect:
        succeeds "check"

        where:
        name | includeLocation
        "with explicit location" | true
        "without explicit location" | false
    }

    @Unroll
    def "handles ivy.xml with dependency declared with #name"() {
        given:

        ivyRepo.module("org.gradle.dep", "dep_module", "1.134")
                .dependsOn("org.gradle.one", "mod_one", "1.1")
                .dependsOn("org.gradle.two", "mod_one", "2.1")
                .dependsOn("org.gradle.two", "mod_two", "2.2")
                .publish()
        ivyRepo.module("org.gradle.one", "mod_one", "1.1").publish()
        ivyRepo.module("org.gradle.two", "mod_one", "2.1").publish()
        ivyRepo.module("org.gradle.two", "mod_two", "2.2").publish()

        ivyRepo.module("org.gradle.test", "test_exclude", "1.134")
                .dependsOn("org.gradle.dep", "dep_module", "1.134")
                .withXml({
                    asNode().dependencies[0].dependency[0].appendNode("exclude", excludeAttributes)
                })
                .publish()

        and:
        buildFile << """
repositories { ivy { url "${ivyRepo.uri}" } }
configurations { compile }
dependencies {
    compile "org.gradle.test:test_exclude:1.134"
}

task check(type: Sync) {
    into "libs"
    from configurations.compile
}
"""

        when:
        succeeds "check"

        then:
        file("libs").assertHasDescendants(jars.toArray(new String[0]))

        where:
        name | excludeAttributes | jars
        "module exclude" | [module: "mod_one"] | ['test_exclude-1.134.jar', 'dep_module-1.134.jar', 'mod_two-2.2.jar']
        "org exclude" | [org: "org.gradle.two"] | ['test_exclude-1.134.jar','dep_module-1.134.jar', 'mod_one-1.1.jar']
        "module and org exclude" | [org: "org.gradle.two", module: "mod_one"] | ['test_exclude-1.134.jar', 'dep_module-1.134.jar', 'mod_one-1.1.jar', 'mod_two-2.2.jar']
    }
}
