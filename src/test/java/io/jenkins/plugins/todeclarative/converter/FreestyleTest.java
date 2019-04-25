package io.jenkins.plugins.todeclarative.converter;

import com.coravy.hudson.plugins.github.GithubProjectProperty;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.BooleanParameterDefinition;
import hudson.model.ChoiceParameterDefinition;
import hudson.model.FreeStyleProject;
import hudson.model.ParameterDefinition;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Result;
import hudson.model.Slave;
import hudson.model.StringParameterDefinition;
import hudson.plugins.git.BranchSpec;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.SubmoduleConfig;
import hudson.plugins.git.UserRemoteConfig;
import hudson.plugins.git.browser.GitRepositoryBrowser;
import hudson.plugins.git.extensions.GitSCMExtension;
import hudson.tasks.LogRotator;
import hudson.tasks.Shell;
import hudson.triggers.SCMTrigger;
import hudson.triggers.TimerTrigger;
import io.jenkins.plugins.todeclarative.converter.ConverterRequest;
import io.jenkins.plugins.todeclarative.converter.ConverterResult;
import io.jenkins.plugins.todeclarative.converter.freestyle.FreestyleToDeclarativeConverter;
import jenkins.model.BuildDiscarderProperty;
import jenkins.model.Jenkins;
import jenkins.triggers.ReverseBuildTrigger;
import org.jenkins.plugins.lockableresources.RequiredResourcesProperty;
import org.jenkinsci.plugins.pipeline.modeldefinition.ast.ModelASTPipelineDef;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class FreestyleTest
{

    @Rule
    public JenkinsRule j  = new JenkinsRule();

    @Test
    public void freestyle_conversion() throws Exception {

        Slave slave = j.createOnlineSlave();
        slave.setLabelString( "FOO_AGENT" );

        String projectName = Long.toString( System.currentTimeMillis() );
        FreeStyleProject p = j.createFreeStyleProject( projectName );
        p.addProperty( new GithubProjectProperty( "https://github.com/olamy/foo") );

        { // git
            List<UserRemoteConfig> repoList = new ArrayList<>();
            repoList.add(new UserRemoteConfig("https://github.com/olamy/foo.git", null,
                                              "master", null));
            repoList.add(new UserRemoteConfig("https://github.com/olamy/bar.git", null,
                                              "patch-1", "credsId"));
            GitSCM gitSCM = new GitSCM( repoList, null, false,
                                        Collections.emptyList(), null, null, Collections.emptyList() );
            p.setScm( gitSCM );
        }

        {
            //int daysToKeep, int numToKeep, int artifactDaysToKeep, int artifactNumToKeep
            LogRotator logRotator = new LogRotator( 1, 2, 3, 4 );
            BuildDiscarderProperty buildDiscarderProperty = new BuildDiscarderProperty( logRotator );
            p.addProperty( buildDiscarderProperty );
        }

        {
            RequiredResourcesProperty requiredResourcesProperty =
                new RequiredResourcesProperty( "beer", null, null, "labelName", null );
            p.addProperty( requiredResourcesProperty );
        }

        {
            List<ParameterDefinition> parametersDefinitions = new ArrayList<>();
            parametersDefinitions.add( new StringParameterDefinition( "str", "defaultValue", "description str", true ) );
            // List<String> toGroovy needs to be fixed
            //parametersDefinitions.add( new ChoiceParameterDefinition( "choice", new String[]{"choice1","choice2"}, "description choice" ) );
            parametersDefinitions.add( new BooleanParameterDefinition( "nameboolean", true, "boolean description" ) );
            ParametersDefinitionProperty parametersDefinitionProperty =
                new ParametersDefinitionProperty( parametersDefinitions );
            p.addProperty( parametersDefinitionProperty );
        }

//  <triggers>
//    <jenkins.triggers.ReverseBuildTrigger>
//      <spec></spec>
//      <upstreamProjects>pipeline, </upstreamProjects>
//      <threshold>
//        <name>FAILURE</name>
//        <ordinal>2</ordinal>
//        <color>RED</color>
//        <completeBuild>true</completeBuild>
//      </threshold>
//    </jenkins.triggers.ReverseBuildTrigger>
//    <hudson.triggers.TimerTrigger>
//      <spec>45 9-16/2 * * 1-5</spec>
//    </hudson.triggers.TimerTrigger>
//    <com.cloudbees.jenkins.GitHubPushTrigger plugin="github@1.29.2">
//      <spec></spec>
//    </com.cloudbees.jenkins.GitHubPushTrigger>
//    <hudson.triggers.SCMTrigger>
//      <spec>45 9-16/2 * * 1-5</spec>
//      <ignorePostCommitHooks>true</ignorePostCommitHooks>
//    </hudson.triggers.SCMTrigger>
//  </triggers>

        {
            ReverseBuildTrigger reverseBuildTrigger = new ReverseBuildTrigger( "pipeline" );
            reverseBuildTrigger.setThreshold( Result.UNSTABLE );
            p.addTrigger( reverseBuildTrigger );

            p.addTrigger(new TimerTrigger( "45 9-16/2 * * 1-5" ));
            SCMTrigger scmTrigger = new SCMTrigger("45 9-16/2 * * 1-5");
            scmTrigger.setIgnorePostCommitHooks( true );
            p.addTrigger( scmTrigger );
        }

        p.getBuildersList().add( new Shell( "pwd" ) );

        FreestyleToDeclarativeConverter converter = Jenkins.get()
            .getExtensionList( FreestyleToDeclarativeConverter.class ).get( 0 );

        Assert.assertTrue( converter.canConvert( p ) );

        ConverterRequest request = new ConverterRequest().job( p ).createdProjectName( "foo-beer" );
        ConverterResult converterResult = new ConverterResult()
            .modelASTPipelineDef( new ModelASTPipelineDef(null));

        converter.convert( request, converterResult);
        String groovy = converterResult.getModelASTPipelineDef().toPrettyGroovy();

        System.out.println(groovy);

        Assert.assertTrue(groovy.contains("branch: 'master'"));
        Assert.assertTrue(groovy.contains("url: 'https://github.com/olamy/foo.git'"));

        Assert.assertTrue(groovy.contains("credentialsId: 'credsId'"));

        Assert.assertEquals( 3, ((WorkflowJob)converterResult.getJob()).getTriggers().size() );

        System.out.println( converterResult.getJob().getProperties() );

    }

    @Test
    public void freestyle_conversion_then_run() throws Exception {

        Slave slave = j.createOnlineSlave();
        slave.setLabelString( "FOO_AGENT" );

        String projectName = Long.toString( System.currentTimeMillis() );
        FreeStyleProject p = j.createFreeStyleProject( projectName );
        p.addProperty( new GithubProjectProperty( "http://github.com/beer/paleale") );

        //int daysToKeep, int numToKeep, int artifactDaysToKeep, int artifactNumToKeep
        LogRotator logRotator = new LogRotator(1, 2,3, 4);
        BuildDiscarderProperty buildDiscarderProperty = new BuildDiscarderProperty( logRotator );
        p.addProperty( buildDiscarderProperty );

        List<ParameterDefinition> parametersDefinitions = new ArrayList<>();
        parametersDefinitions.add( new StringParameterDefinition( "str", "defaultValue", "description str", true ) );
        // List<String> toGroovy needs to be fixed
        //parametersDefinitions.add( new ChoiceParameterDefinition( "choice", new String[]{"choice1","choice2"}, "description choice" ) );
        parametersDefinitions.add( new BooleanParameterDefinition("nameboolean", true, "boolean description") );
        ParametersDefinitionProperty parametersDefinitionProperty = new ParametersDefinitionProperty(parametersDefinitions);
        p.addProperty( parametersDefinitionProperty );


        p.getBuildersList().add( new Shell( "pwd" ) );
        p.getBuildersList().add( new Shell( "ls -lrt" ) );
        p.getBuildersList().add( new Shell( "echo $str" ) );

        FreestyleToDeclarativeConverter converter = Jenkins.get()
            .getExtensionList( FreestyleToDeclarativeConverter.class ).get( 0 );

        Assert.assertTrue( converter.canConvert( p ) );

        ConverterRequest request = new ConverterRequest().job( p ).createdProjectName( "foo-beer" );
        ConverterResult converterResult = new ConverterResult()
            .modelASTPipelineDef( new ModelASTPipelineDef(null));

        converter.convert( request, converterResult);
        String groovy = converterResult.getModelASTPipelineDef().toPrettyGroovy();

        System.out.println( groovy );

        System.out.println( converterResult.getJob().getProperties() );

        WorkflowRun run =( (WorkflowJob)converterResult.getJob()).scheduleBuild2( 0).get();
        j.waitForCompletion( run );
        j.assertBuildStatus( Result.SUCCESS, run);

    }

    @Test
    public void freestyle_conversion_only_Jenkinsfile() throws Exception {

        Slave slave = j.createOnlineSlave();
        slave.setLabelString( "FOO_AGENT" );

        String projectName = Long.toString( System.currentTimeMillis() );
        FreeStyleProject p = j.createFreeStyleProject( projectName );
        p.addProperty( new GithubProjectProperty( "http://github.com/beer/paleale") );

        //int daysToKeep, int numToKeep, int artifactDaysToKeep, int artifactNumToKeep
        LogRotator logRotator = new LogRotator(1, 2,3, 4);
        BuildDiscarderProperty buildDiscarderProperty = new BuildDiscarderProperty( logRotator );
        p.addProperty( buildDiscarderProperty );

        List<ParameterDefinition> parametersDefinitions = new ArrayList<>();
        parametersDefinitions.add( new StringParameterDefinition( "str", "defaultValue", "description str", true ) );
        // List<String> toGroovy needs to be fixed
        //parametersDefinitions.add( new ChoiceParameterDefinition( "choice", new String[]{"choice1","choice2"}, "description choice" ) );
        parametersDefinitions.add( new BooleanParameterDefinition("nameboolean", true, "boolean description") );
        ParametersDefinitionProperty parametersDefinitionProperty = new ParametersDefinitionProperty(parametersDefinitions);
        p.addProperty( parametersDefinitionProperty );


        p.getBuildersList().add( new Shell( "pwd" ) );

        FreestyleToDeclarativeConverter converter = Jenkins.get()
            .getExtensionList( FreestyleToDeclarativeConverter.class ).get( 0 );

        Assert.assertTrue( converter.canConvert( p ) );

        ConverterRequest request = new ConverterRequest().job( p ).createProject( false );
        ConverterResult converterResult = new ConverterResult()
            .modelASTPipelineDef( new ModelASTPipelineDef(null));

        converter.convert( request, converterResult);
        String groovy = converterResult.getModelASTPipelineDef().toPrettyGroovy();

        System.out.println( groovy );


    }

}
