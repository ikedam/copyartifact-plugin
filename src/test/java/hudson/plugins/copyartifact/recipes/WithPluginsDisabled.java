/*
 * The MIT License
 * 
 * Copyright (c) 2014 IKEDA Yasuyuki
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

package hudson.plugins.copyartifact.recipes;

import hudson.DisablingTestPluginManager;

import java.io.File;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.jvnet.hudson.test.HudsonTestCase;
import org.jvnet.hudson.test.JenkinsRecipe;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.recipes.Recipe;

/**
 * Disables specified plugin.
 */
@Documented
@Recipe(WithPluginsDisabled.RunnerImpl.class)
@JenkinsRecipe(WithPluginsDisabled.RuleRunnerImpl.class)
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface WithPluginsDisabled {
    /**
     * Name of the plugin. Specify artifact ID.
     */
    public String[] value();
    
    /**
     * Processor for {@link HudsonTestCase}
     */
    public class RunnerImpl extends Recipe.Runner<WithPluginsDisabled> {
        private WithPluginsDisabled recipe;
        
        @Override
        public void setup(HudsonTestCase testCase, WithPluginsDisabled recipe) throws Exception {
            this.recipe = recipe;
        }
        
        @Override
        public void decorateHome(HudsonTestCase testCase, File home) throws Exception {
            testCase.setPluginManager(new DisablingTestPluginManager(home, recipe.value()));
        }
    }
    
    /**
     * Processor for {@link JenkinsRule}
     */
    public class RuleRunnerImpl extends JenkinsRecipe.Runner<WithPluginsDisabled> {
        private WithPluginsDisabled recipe;
        
        @Override
        public void setup(JenkinsRule jenkinsRule, WithPluginsDisabled recipe) throws Exception {
            this.recipe = recipe;
        }
        
        @Override
        public void decorateHome(JenkinsRule jenkinsRule, File home) throws Exception {
            jenkinsRule.setPluginManager(new DisablingTestPluginManager(home, recipe.value()));
        }
    }
}
