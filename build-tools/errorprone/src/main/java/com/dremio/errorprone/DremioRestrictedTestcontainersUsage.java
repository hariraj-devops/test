/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.errorprone;

import static com.google.errorprone.BugPattern.LinkType.NONE;
import static com.google.errorprone.BugPattern.SeverityLevel.ERROR;
import static com.google.errorprone.matchers.Description.NO_MATCH;
import static com.google.errorprone.matchers.Matchers.allOf;
import static com.google.errorprone.matchers.Matchers.isSubtypeOf;
import static com.google.errorprone.matchers.Matchers.not;
import static com.sun.source.tree.MemberReferenceTree.ReferenceMode.NEW;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.bugpatterns.BugChecker.MemberReferenceTreeMatcher;
import com.google.errorprone.bugpatterns.BugChecker.NewClassTreeMatcher;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.Matcher;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;

@BugPattern(
    summary = "Use the provided Dremio Testcontainers modules",
    severity = ERROR,
    linkType = NONE)
@AutoService(BugChecker.class)
public class DremioRestrictedTestcontainersUsage extends BugChecker
    implements NewClassTreeMatcher, MemberReferenceTreeMatcher {

  private static final Matcher<Tree> IS_RESTRICTED =
      allOf(
          isSubtypeOf("org.testcontainers.containers.GenericContainer"),
          not(isSubtypeOf("com.dremio.testcontainers.DremioContainer")));

  @Override
  public Description matchNewClass(NewClassTree tree, VisitorState state) {
    return match(tree, state);
  }

  @Override
  public Description matchMemberReference(MemberReferenceTree tree, VisitorState state) {
    if (tree.getMode() == NEW) {
      return match(tree.getQualifierExpression(), state);
    }
    return NO_MATCH;
  }

  private Description match(Tree tree, VisitorState state) {
    if (IS_RESTRICTED.matches(tree, state)) {
      return buildDescription(tree)
          .setMessage(
              "Use the provided Dremio Testcontainers modules. See "
                  + "ZookeeperContainer, NatsContainer, "
                  + "PubSubEmulatorContainer, MongoDBContainer & "
                  + "RedisContainer. Do not suppress this error.")
          .build();
    }
    return NO_MATCH;
  }
}
