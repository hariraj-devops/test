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
package com.dremio.exec.planner.sql.handlers;

import static com.dremio.exec.ExecConstants.ENABLE_USE_VERSION_SYNTAX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dremio.catalog.model.VersionContext;
import com.dremio.common.exceptions.UserException;
import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.catalog.VersionedPlugin;
import com.dremio.exec.planner.sql.handlers.direct.SimpleCommandResult;
import com.dremio.exec.planner.sql.parser.SqlDropBranch;
import com.dremio.exec.store.NoDefaultBranchException;
import com.dremio.exec.store.ReferenceAlreadyExistsException;
import com.dremio.exec.store.ReferenceConflictException;
import com.dremio.exec.store.ReferenceNotFoundException;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.exec.work.foreman.ForemanSetupException;
import com.dremio.options.OptionManager;
import com.dremio.plugins.dataplane.store.DataplanePlugin;
import com.dremio.sabot.rpc.user.UserSession;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceNotFoundException;
import com.dremio.test.DremioTest;
import java.util.Arrays;
import java.util.List;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.quality.Strictness;

/** Tests for DROP BRANCH SQL. */
public class TestDropBranchHandler extends DremioTest {

  private static final String DEFAULT_SOURCE_NAME = "dataplane_source_1";
  private static final String NON_EXISTENT_SOURCE_NAME = "non_exist";
  private static final String SESSION_SOURCE_NAME = "session_source";
  private static final String DEFAULT_BRANCH_NAME = "branchName";
  private static final String DEFAULT_COMMIT_HASH = "0123456789abcdeff";
  private static final SqlDropBranch DEFAULT_INPUT =
      new SqlDropBranch(
          SqlParserPos.ZERO,
          SqlLiteral.createBoolean(true, SqlParserPos.ZERO),
          new SqlIdentifier(DEFAULT_BRANCH_NAME, SqlParserPos.ZERO),
          new SqlIdentifier(DEFAULT_COMMIT_HASH, SqlParserPos.ZERO),
          SqlLiteral.createBoolean(false, SqlParserPos.ZERO),
          new SqlIdentifier(DEFAULT_SOURCE_NAME, SqlParserPos.ZERO));
  private static final SqlDropBranch NO_SOURCE_INPUT =
      new SqlDropBranch(
          SqlParserPos.ZERO,
          SqlLiteral.createBoolean(true, SqlParserPos.ZERO),
          new SqlIdentifier(DEFAULT_BRANCH_NAME, SqlParserPos.ZERO),
          new SqlIdentifier(DEFAULT_COMMIT_HASH, SqlParserPos.ZERO),
          SqlLiteral.createBoolean(false, SqlParserPos.ZERO),
          null);
  private static final SqlDropBranch NON_EXISTENT_SOURCE_INPUT =
      new SqlDropBranch(
          SqlParserPos.ZERO,
          SqlLiteral.createBoolean(true, SqlParserPos.ZERO),
          new SqlIdentifier(DEFAULT_BRANCH_NAME, SqlParserPos.ZERO),
          new SqlIdentifier(DEFAULT_COMMIT_HASH, SqlParserPos.ZERO),
          SqlLiteral.createBoolean(false, SqlParserPos.ZERO),
          new SqlIdentifier(NON_EXISTENT_SOURCE_NAME, SqlParserPos.ZERO));
  private static final SqlDropBranch IF_EXISTS_INPUT =
      new SqlDropBranch(
          SqlParserPos.ZERO,
          SqlLiteral.createBoolean(false, SqlParserPos.ZERO),
          new SqlIdentifier(DEFAULT_BRANCH_NAME, SqlParserPos.ZERO),
          new SqlIdentifier(DEFAULT_COMMIT_HASH, SqlParserPos.ZERO),
          SqlLiteral.createBoolean(false, SqlParserPos.ZERO),
          new SqlIdentifier(DEFAULT_SOURCE_NAME, SqlParserPos.ZERO));

  @Rule public MockitoRule rule = MockitoJUnit.rule().strictness(Strictness.STRICT_STUBS);

  @Mock private OptionManager optionManager;
  @Mock private Catalog catalog;
  @Mock private UserSession userSession;
  @Mock private DataplanePlugin dataplanePlugin;

  @InjectMocks private DropBranchHandler handler;

  @Test
  public void dropBranchSupportKeyDisabledThrows() {
    // Arrange
    when(optionManager.getOption(ENABLE_USE_VERSION_SYNTAX)).thenReturn(false);

    // Act + Assert
    assertThatThrownBy(() -> handler.toResult("", DEFAULT_INPUT))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("DROP BRANCH")
        .hasMessageContaining("not supported");
  }

  @Test
  public void dropBranchNonExistentSource() {
    // Arrange
    when(optionManager.getOption(ENABLE_USE_VERSION_SYNTAX)).thenReturn(true);
    NamespaceNotFoundException notFoundException = new NamespaceNotFoundException("Cannot access");
    UserException nonExistException =
        UserException.validationError(notFoundException)
            .message("Tried to access non-existent source [%s].", NON_EXISTENT_SOURCE_NAME)
            .build();
    when(catalog.getSource(NON_EXISTENT_SOURCE_NAME)).thenThrow(nonExistException);
    when(userSession.getSessionVersionForSource(NON_EXISTENT_SOURCE_NAME))
        .thenReturn(mock(VersionContext.class));
    // Act + Assert
    assertThatThrownBy(() -> handler.toResult("", NON_EXISTENT_SOURCE_INPUT))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("Tried to access non-existent source");
  }

  @Test
  public void dropBranchSucceed()
      throws ForemanSetupException, ReferenceConflictException, ReferenceNotFoundException {
    // Arrange
    setUpSupportKeyAndPlugin();
    doNothing().when(dataplanePlugin).dropBranch(DEFAULT_BRANCH_NAME, DEFAULT_COMMIT_HASH);
    when(userSession.getSessionVersionForSource(DEFAULT_SOURCE_NAME))
        .thenReturn(mock(VersionContext.class));
    // Act
    List<SimpleCommandResult> result = handler.toResult("", DEFAULT_INPUT);

    // Assert
    assertThat(result).isNotEmpty();
    assertTrue(result.get(0).ok);
    assertThat(result.get(0).summary)
        .contains("dropped")
        .contains(DEFAULT_BRANCH_NAME)
        .contains(DEFAULT_SOURCE_NAME);
  }

  @Test
  public void dropBranchEmptySourceUsesSessionContext()
      throws ReferenceNotFoundException,
          NoDefaultBranchException,
          ReferenceConflictException,
          ForemanSetupException {
    // Arrange
    setUpSupportKeyAndPluginAndSessionContext();
    doNothing().when(dataplanePlugin).dropBranch(DEFAULT_BRANCH_NAME, DEFAULT_COMMIT_HASH);

    // Act
    List<SimpleCommandResult> result = handler.toResult("", NO_SOURCE_INPUT);

    // Assert
    assertThat(result).isNotEmpty();
    assertThat(result.get(0).ok).isTrue();
    assertThat(result.get(0).summary)
        .contains("dropped")
        .contains(DEFAULT_BRANCH_NAME)
        .contains(SESSION_SOURCE_NAME);
  }

  // test force drop

  @Test
  public void dropBranchIfNotExistsDoesNotExistSucceeds()
      throws ReferenceNotFoundException,
          NoDefaultBranchException,
          ReferenceConflictException,
          ReferenceAlreadyExistsException,
          ForemanSetupException {
    // Arrange
    setUpSupportKeyAndPlugin();
    doNothing().when(dataplanePlugin).dropBranch(DEFAULT_BRANCH_NAME, DEFAULT_COMMIT_HASH);
    when(userSession.getSessionVersionForSource(DEFAULT_SOURCE_NAME))
        .thenReturn(mock(VersionContext.class));
    // Act
    List<SimpleCommandResult> result = handler.toResult("", IF_EXISTS_INPUT);

    // Assert
    assertThat(result).isNotEmpty();
    assertThat(result.get(0).ok).isTrue();
    assertThat(result.get(0).summary)
        .contains("dropped")
        .contains(DEFAULT_BRANCH_NAME)
        .contains(DEFAULT_SOURCE_NAME);
  }

  @Test
  public void dropBranchIfNotExistsDoesExistNoOp()
      throws ReferenceNotFoundException,
          NoDefaultBranchException,
          ReferenceConflictException,
          ReferenceAlreadyExistsException,
          ForemanSetupException {
    // Arrange
    setUpSupportKeyAndPlugin();
    doThrow(ReferenceNotFoundException.class)
        .when(dataplanePlugin)
        .dropBranch(DEFAULT_BRANCH_NAME, DEFAULT_COMMIT_HASH);
    when(userSession.getSessionVersionForSource(DEFAULT_SOURCE_NAME))
        .thenReturn(mock(VersionContext.class));
    // Act
    List<SimpleCommandResult> result = handler.toResult("", IF_EXISTS_INPUT);

    // Assert
    assertThat(result).isNotEmpty();
    assertThat(result.get(0).ok).isTrue();
    assertThat(result.get(0).summary)
        .contains("not found")
        .contains(DEFAULT_BRANCH_NAME)
        .contains(DEFAULT_SOURCE_NAME);
  }

  @Test
  public void dropBranchWrongSourceThrows()
      throws ReferenceNotFoundException, NoDefaultBranchException, ReferenceConflictException {
    // Arrange
    when(optionManager.getOption(ENABLE_USE_VERSION_SYNTAX)).thenReturn(true);
    when(catalog.getSource(DEFAULT_SOURCE_NAME)).thenReturn(mock(StoragePlugin.class));
    when(userSession.getSessionVersionForSource(DEFAULT_SOURCE_NAME))
        .thenReturn(mock(VersionContext.class));
    // Act + Assert
    assertThatThrownBy(() -> handler.toResult("", DEFAULT_INPUT))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("does not support")
        .hasMessageContaining(DEFAULT_SOURCE_NAME);
    verify(userSession, never()).setSessionVersionForSource(any(), any());
  }

  @Test
  public void dropBranchWrongSourceFromContextThrows() {
    // Arrange
    when(optionManager.getOption(ENABLE_USE_VERSION_SYNTAX)).thenReturn(true);
    when(catalog.getSource(SESSION_SOURCE_NAME)).thenReturn(dataplanePlugin);
    when(userSession.getDefaultSchemaPath())
        .thenReturn(new NamespaceKey(Arrays.asList(SESSION_SOURCE_NAME, "unusedFolder")));
    when(userSession.getSessionVersionForSource(SESSION_SOURCE_NAME))
        .thenReturn(mock(VersionContext.class));
    when(catalog.getSource(SESSION_SOURCE_NAME)).thenReturn(mock(StoragePlugin.class));

    // Act + Assert
    assertThatThrownBy(() -> handler.toResult("", NO_SOURCE_INPUT))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("does not support")
        .hasMessageContaining(SESSION_SOURCE_NAME);
    verify(userSession, never()).setSessionVersionForSource(any(), any());
  }

  @Test
  public void dropBranchNullSourceFromContextThrows() {
    // Arrange
    when(optionManager.getOption(ENABLE_USE_VERSION_SYNTAX)).thenReturn(true);
    when(userSession.getDefaultSchemaPath()).thenReturn(null);

    // Act + Assert
    assertThatThrownBy(() -> handler.toResult("", NO_SOURCE_INPUT))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("was not specified");
    verify(userSession, never()).setSessionVersionForSource(any(), any());
  }

  @Test
  public void dropBranchNoCommitHashNoForce()
      throws ReferenceConflictException, ReferenceNotFoundException, ForemanSetupException {
    setUpSupportKeyAndPlugin();
    // Constants
    final SqlDropBranch dropBranchWithoutForceOrCommitHash =
        new SqlDropBranch(
            SqlParserPos.ZERO,
            SqlLiteral.createBoolean(false, SqlParserPos.ZERO),
            new SqlIdentifier(DEFAULT_BRANCH_NAME, SqlParserPos.ZERO),
            null,
            SqlLiteral.createBoolean(false, SqlParserPos.ZERO),
            new SqlIdentifier(DEFAULT_SOURCE_NAME, SqlParserPos.ZERO));

    // Arrange
    when(optionManager.getOption(ENABLE_USE_VERSION_SYNTAX)).thenReturn(true);
    when(userSession.getSessionVersionForSource(DEFAULT_SOURCE_NAME))
        .thenReturn(mock(VersionContext.class));

    // Act + Assert
    List<SimpleCommandResult> result = handler.toResult("", dropBranchWithoutForceOrCommitHash);

    assertThat(result).isNotEmpty();
    assertThat(result.get(0).ok).isTrue();
    assertThat(result.get(0).summary)
        .contains("dropped")
        .contains(DEFAULT_BRANCH_NAME)
        .contains(DEFAULT_SOURCE_NAME);
  }

  @Test
  public void dropBranchNotFoundThrows()
      throws ReferenceNotFoundException, NoDefaultBranchException, ReferenceConflictException {
    // Arrange
    setUpSupportKeyAndPlugin();
    doThrow(ReferenceNotFoundException.class)
        .when(dataplanePlugin)
        .dropBranch(DEFAULT_BRANCH_NAME, DEFAULT_COMMIT_HASH);
    when(userSession.getSessionVersionForSource(DEFAULT_SOURCE_NAME))
        .thenReturn(mock(VersionContext.class));
    // Act + Assert
    assertThatThrownBy(() -> handler.toResult("", DEFAULT_INPUT))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("not found")
        .hasMessageContaining(DEFAULT_BRANCH_NAME)
        .hasMessageContaining(DEFAULT_SOURCE_NAME);
    verify(userSession, never()).setSessionVersionForSource(any(), any());
  }

  @Test
  public void dropBranchConflictThrows()
      throws ReferenceNotFoundException, NoDefaultBranchException, ReferenceConflictException {
    // Arrange
    setUpSupportKeyAndPlugin();
    doThrow(ReferenceConflictException.class)
        .when(dataplanePlugin)
        .dropBranch(DEFAULT_BRANCH_NAME, DEFAULT_COMMIT_HASH);
    when(userSession.getSessionVersionForSource(DEFAULT_SOURCE_NAME))
        .thenReturn(mock(VersionContext.class));
    // Act + Assert
    assertThatThrownBy(() -> handler.toResult("", DEFAULT_INPUT))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("has conflict")
        .hasMessageContaining(DEFAULT_BRANCH_NAME)
        .hasMessageContaining(DEFAULT_SOURCE_NAME);
    verify(userSession, never()).setSessionVersionForSource(any(), any());
  }

  private void setUpSupportKeyAndPlugin() {
    when(optionManager.getOption(ENABLE_USE_VERSION_SYNTAX)).thenReturn(true);
    when(catalog.getSource(DEFAULT_SOURCE_NAME)).thenReturn(dataplanePlugin);
    when(dataplanePlugin.isWrapperFor(VersionedPlugin.class)).thenReturn(true);
    when(dataplanePlugin.unwrap(VersionedPlugin.class)).thenReturn(dataplanePlugin);
  }

  private void setUpSupportKeyAndPluginAndSessionContext() {
    when(optionManager.getOption(ENABLE_USE_VERSION_SYNTAX)).thenReturn(true);
    when(catalog.getSource(SESSION_SOURCE_NAME)).thenReturn(dataplanePlugin);
    when(dataplanePlugin.isWrapperFor(VersionedPlugin.class)).thenReturn(true);
    when(dataplanePlugin.unwrap(VersionedPlugin.class)).thenReturn(dataplanePlugin);
    when(userSession.getDefaultSchemaPath())
        .thenReturn(new NamespaceKey(Arrays.asList(SESSION_SOURCE_NAME, "unusedFolder")));
    when(userSession.getSessionVersionForSource(SESSION_SOURCE_NAME))
        .thenReturn(mock(VersionContext.class));
  }
}
