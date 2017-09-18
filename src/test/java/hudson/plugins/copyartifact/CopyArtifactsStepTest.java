/*
 * The MIT License
 *
 * Copyright (c) 2017 IKEDA Yasuyuki
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package hudson.plugins.copyartifact;

import java.io.IOException;

import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import hudson.model.FreeStyleProject;
import hudson.model.Job;
import hudson.plugins.copyartifact.testutils.FileWriteBuilder;
import hudson.tasks.ArtifactArchiver;

/**
 * Tests for {@link CopyArtifactsStep}
 *
 * This tests only some simple pipeline expressions
 * as features of {@link CopyArtifact} is already testes in
 * other tests.
 */
public class CopyArtifactsStepTest {
    @ClassRule
    public static JenkinsRule r = new JenkinsRule();

    @After
    public void deleteAllJobs() throws Exception {
        for (Job<?, ?> job: r.jenkins.getItems(Job.class)) {
            job.delete();
        }
    }

    private WorkflowJob createWorkflow(String name, String script) throws IOException {
        WorkflowJob job = r.jenkins.createProject(WorkflowJob.class, name);
        job.setDefinition(new CpsFlowDefinition("node {" + script + "}", true));
        return job;
    }

    @Test
    public void testDownstreamBuildSelector() throws Exception {
        // upstream (freestyle) -> copiee (fresstyle)
        // copier (pipeline) copies from copiee, which is downstream of `upstream`.
        // DownstreamBuildSelector support detecting relations between only `AbstractProject`s.

        FreeStyleProject upstream = r.createFreeStyleProject("upstream");
        upstream.getBuildersList().add(new FileWriteBuilder("upstream_artifact.txt", "${BUILD_TAG}"));
        ArtifactArchiver aa = new ArtifactArchiver("upstream_artifact.txt");
        aa.setFingerprint(true);        // important to have Jenkins track builds
        upstream.getPublishersList().add(aa);

        FreeStyleProject copiee = r.createFreeStyleProject("copiee");
        CopyArtifact ca = new CopyArtifact("upstream");
        ca.setFingerprintArtifacts(true);       // important to have Jenkins track builds
        copiee.getBuildersList().add(ca);
        copiee.getBuildersList().add(new FileWriteBuilder("artifact.txt", "foobar"));
        copiee.getPublishersList().add(new ArtifactArchiver("artifact.txt"));

        r.jenkins.rebuildDependencyGraph();

        r.assertBuildStatusSuccess(upstream.scheduleBuild2(0));
        r.assertBuildStatusSuccess(copiee.scheduleBuild2(0));
        r.waitUntilNoActivity();

        WorkflowJob copier = createWorkflow(
            "copier",
            "copyArtifacts(project: 'copiee', selector: downstream(upstreamProjectName: 'upstream', upstreamBuildNumber: '1'));"
            + "echo readFile('artifact.txt')"
        );
        r.assertLogContains("foobar", r.assertBuildStatusSuccess(copier.scheduleBuild2(0)));
    }

    @Test
    public void testLastCompletedBuildSelector() throws Exception {
        WorkflowJob copiee = createWorkflow(
            "copiee",
            "writeFile text: 'foobar', file: 'artifact.txt';"
            + "archive includes: 'artifact.txt';"
        );
        r.assertBuildStatusSuccess(copiee.scheduleBuild2(0));

        WorkflowJob copier = createWorkflow(
            "copier",
            "copyArtifacts(project: 'copiee', selector: lastCompleted());"
            + "echo readFile('artifact.txt')"
        );
        r.assertLogContains("foobar", r.assertBuildStatusSuccess(copier.scheduleBuild2(0)));
    }

    @Test
    public void testParameterizedBuildSelector() throws Exception {
        WorkflowJob copiee = createWorkflow(
            "copiee",
            "writeFile text: 'foobar', file: 'artifact.txt';"
            + "archive includes: 'artifact.txt';"
        );
        r.assertBuildStatusSuccess(copiee.scheduleBuild2(0));

        WorkflowJob copier = createWorkflow(
            "copier",
            "copyArtifacts(project: 'copiee', selector: buildParameter('<StatusBuildSelector/>'));"
            + "echo readFile('artifact.txt')"
        );
        r.assertLogContains("foobar", r.assertBuildStatusSuccess(copier.scheduleBuild2(0)));
    }

    @Test
    public void testPermalinkBuildSelector() throws Exception {
        WorkflowJob copiee = createWorkflow(
            "copiee",
            "writeFile text: 'foobar', file: 'artifact.txt';"
            + "archive includes: 'artifact.txt';"
        );
        r.assertBuildStatusSuccess(copiee.scheduleBuild2(0));

        WorkflowJob copier = createWorkflow(
            "copier",
            "copyArtifacts(project: 'copiee', selector: permalink('lastStableBuild'));"
            + "echo readFile('artifact.txt')"
        );
        r.assertLogContains("foobar", r.assertBuildStatusSuccess(copier.scheduleBuild2(0)));
    }

    @Test
    public void testSavedBuildSelector() throws Exception {
        WorkflowJob copiee = createWorkflow(
            "copiee",
            "writeFile text: 'foobar', file: 'artifact.txt';"
            + "archive includes: 'artifact.txt';"
        );
        r.assertBuildStatusSuccess(copiee.scheduleBuild2(0)).keepLog();

        WorkflowJob copier = createWorkflow(
            "copier",
            "copyArtifacts(project: 'copiee', selector: latestSavedBuild());"
            + "echo readFile('artifact.txt')"
        );
        r.assertLogContains("foobar", r.assertBuildStatusSuccess(copier.scheduleBuild2(0)));
    }

    @Test
    public void testSpecificBuildSelector() throws Exception {
        WorkflowJob copier = createWorkflow(
            "copier",
            "copyArtifacts(project: 'copiee', selector: specific(\"${build('copiee').number}\"));"
            + "echo readFile('artifact.txt')"
        );
        createWorkflow(
            "copiee",
            "writeFile text: 'foobar', file: 'artifact.txt';"
            + "archive includes: 'artifact.txt';"
        );
        r.assertLogContains("foobar", r.assertBuildStatusSuccess(copier.scheduleBuild2(0)));
    }

    @Test
    public void testStatusBuildSelector() throws Exception {
        WorkflowJob copiee = createWorkflow(
            "copiee",
            "writeFile text: 'foobar', file: 'artifact.txt';"
            + "archive includes: 'artifact.txt';"
        );
        r.assertBuildStatusSuccess(copiee.scheduleBuild2(0));

        WorkflowJob copier = createWorkflow(
            "copier",
            "copyArtifacts(project: 'copiee', selector: lastSuccessful());"
            + "echo readFile('artifact.txt')"
        );
        r.assertLogContains("foobar", r.assertBuildStatusSuccess(copier.scheduleBuild2(0)));
    }

    @Test
    public void testTriggeredBuildSelector() throws Exception {
        WorkflowJob copiee = createWorkflow(
            "copiee",
            "writeFile text: 'foobar', file: 'artifact.txt';"
            + "archive includes: 'artifact.txt';"
            + "build('copier');"
        );

        WorkflowJob copier = createWorkflow(
            "copier",
            "copyArtifacts(project: 'copiee', selector: upstream());"
            + "echo readFile('artifact.txt')"
        );
        r.assertBuildStatusSuccess(copiee.scheduleBuild2(0));
        r.assertLogContains("foobar",r.assertBuildStatusSuccess(copier.getLastBuild()));
    }

    @Test
    public void testWorkspaceBuildSelector() throws Exception {
        // workspace is useless for pipeline jobs
        FreeStyleProject copiee = r.createFreeStyleProject("copiee");
        copiee.getBuildersList().add(new FileWriteBuilder("artifact.txt", "foobar"));
        copiee.getPublishersList().add(new ArtifactArchiver("artifact.txt"));
        r.assertBuildStatusSuccess(copiee.scheduleBuild2(0));

        WorkflowJob copier = createWorkflow(
            "copier",
            "copyArtifacts(project: 'copiee', selector: workspace());"
            + "echo readFile('artifact.txt')"
        );
        r.assertLogContains("foobar",r.assertBuildStatusSuccess(copier.scheduleBuild2(0)));
    }
}
