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
package com.dremio.plugins.s3.store;

import static com.dremio.plugins.Constants.DREMIO_ENABLE_BUCKET_DISCOVERY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.AccessControlList;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.Bucket;
import com.amazonaws.services.s3.model.CanonicalGrantee;
import com.amazonaws.services.s3.model.Grant;
import com.amazonaws.services.s3.model.ListObjectsV2Request;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.amazonaws.services.s3.model.Permission;
import com.dremio.plugins.util.CloseableResource;
import com.dremio.plugins.util.ContainerAccessDeniedException;
import com.dremio.plugins.util.ContainerNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.s3a.AnonymousAWSCredentialsProvider;
import org.apache.hadoop.fs.s3a.Constants;
import org.apache.hadoop.fs.s3a.S3AFileSystem;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.MockedStatic;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.StsClientBuilder;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest;
import software.amazon.awssdk.services.sts.model.StsException;

/** Test the S3FileSystem class. */
public class TestS3FileSystem {
  @Test
  public void testValidRegionFromEndpoint() {
    Region r = S3FileSystem.getAwsRegionFromEndpoint("s3-eu-central-1.amazonaws.com");
    Assert.assertEquals(Region.EU_CENTRAL_1, r);

    r = S3FileSystem.getAwsRegionFromEndpoint("s3-us-gov-west-1.amazonaws.com");
    Assert.assertEquals(Region.US_GOV_WEST_1, r);

    r = S3FileSystem.getAwsRegionFromEndpoint("s3.ap-southeast-1.amazonaws.com");
    Assert.assertEquals(Region.AP_SOUTHEAST_1, r);

    r = S3FileSystem.getAwsRegionFromEndpoint("s3.dualstack.ca-central-1.amazonaws.com");
    Assert.assertEquals(Region.CA_CENTRAL_1, r);

    r = S3FileSystem.getAwsRegionFromEndpoint("dremio.s3-control.cn-north-1.amazonaws.com.cn");
    Assert.assertEquals(Region.CN_NORTH_1, r);

    r =
        S3FileSystem.getAwsRegionFromEndpoint(
            "accountId.s3-control.dualstack.eu-central-1.amazonaws.com");
    Assert.assertEquals(Region.EU_CENTRAL_1, r);

    r = S3FileSystem.getAwsRegionFromEndpoint("s3-accesspoint.eu-west-1.amazonaws.com");
    Assert.assertEquals(Region.EU_WEST_1, r);

    r = S3FileSystem.getAwsRegionFromEndpoint("s3-accesspoint.dualstack.sa-east-1.amazonaws.com");
    Assert.assertEquals(Region.SA_EAST_1, r);

    r = S3FileSystem.getAwsRegionFromEndpoint("s3-fips.us-gov-west-1.amazonaws.com");
    Assert.assertEquals(Region.US_GOV_WEST_1, r);

    r = S3FileSystem.getAwsRegionFromEndpoint("s3.dualstack.us-gov-east-1.amazonaws.com");
    Assert.assertEquals(Region.US_GOV_EAST_1, r);
  }

  @Test
  public void testInvalidRegionFromEndpoint() {
    Region r = S3FileSystem.getAwsRegionFromEndpoint("us-west-1");
    Assert.assertEquals(Region.US_EAST_1, r);

    r = S3FileSystem.getAwsRegionFromEndpoint("s3-eu-central-1");
    Assert.assertEquals(Region.US_EAST_1, r);

    r = S3FileSystem.getAwsRegionFromEndpoint("abc");
    Assert.assertEquals(Region.US_EAST_1, r);

    r = S3FileSystem.getAwsRegionFromEndpoint("");
    Assert.assertEquals(Region.US_EAST_1, r);

    r = S3FileSystem.getAwsRegionFromEndpoint(null);
    Assert.assertEquals(Region.US_EAST_1, r);
  }

  @Test
  public void testUnknownContainerExists() throws Exception {
    TestExtendedS3FileSystem fs = new TestExtendedS3FileSystem();
    AmazonS3 mockedS3Client = mock(AmazonS3.class);
    when(mockedS3Client.doesBucketExistV2(any(String.class))).thenReturn(true);
    ListObjectsV2Result result = new ListObjectsV2Result();
    result.setBucketName("testunknown");
    when(mockedS3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(result);

    fs.setCustomClient(mockedS3Client);
    assertNotNull(fs.getUnknownContainer("testunknown"));
  }

  @Test
  public void testUnknownContainerUnderlyingFSClosesOnFSClose() throws Exception {
    final TestExtendedS3FileSystem dremioFS = new TestExtendedS3FileSystem();
    final String bucketName = "someContainer";

    // Set up S3 Mock
    final AmazonS3 s3Mock = mock(AmazonS3.class);
    final ListObjectsV2Result result = new ListObjectsV2Result();
    result.setBucketName(bucketName);

    when(s3Mock.doesBucketExistV2(any(String.class))).thenReturn(true);
    when(s3Mock.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(result);
    when(s3Mock.listBuckets()).thenReturn(List.of(new Bucket(bucketName)));

    dremioFS.setCustomClient(s3Mock);

    // Set up Verify Mocks to simulate a success
    final StsClient mockedClient = mock(StsClient.class);
    final StsClientBuilder mockedClientBuilder = mock(StsClientBuilder.class);
    when(mockedClientBuilder.credentialsProvider(any(AwsCredentialsProvider.class)))
        .thenReturn(mockedClientBuilder);
    when(mockedClientBuilder.region(any(Region.class))).thenReturn(mockedClientBuilder);
    when(mockedClientBuilder.build()).thenReturn(mockedClient);
    when(mockedClient.getCallerIdentity(any(GetCallerIdentityRequest.class))).thenReturn(null);

    // Mocking static calls for Hadoop's FileSystem in order to return our custom S3AFileSystem,
    // which can be used to keep track of closed state without relying on static properties.
    try (MockedStatic<FileSystem> mockedHadoopFS = mockStatic(FileSystem.class);
        MockedStatic<StsClient> mockedStsClient = mockStatic(StsClient.class)) {
      final TestExtendS3AFileSystem fakeHadoopFs = new TestExtendS3AFileSystem(bucketName);
      mockedHadoopFS.when(() -> FileSystem.get(any(), any())).thenReturn(fakeHadoopFs);
      mockedStsClient.when(StsClient::builder).thenReturn(mockedClientBuilder);

      // Initialize the DremioFS and call getFileStatus to ensure fakeHadoopFs gets cached.
      dremioFS.initialize(new URI("s3a://" + bucketName), new Configuration());
      dremioFS.getFileStatus(new Path("/" + bucketName));

      // Assert underlying cached fakeHadoopFs is closed when DremioFS is closed.
      assertFalse(fakeHadoopFs.wasFSClosed());
      dremioFS.close();
      assertTrue(fakeHadoopFs.wasFSClosed());
    }
  }

  @Test(expected = ContainerNotFoundException.class)
  public void testUnknownContainerNotExists() throws IOException {
    TestExtendedS3FileSystem fs = new TestExtendedS3FileSystem();
    AmazonS3 mockedS3Client = mock(AmazonS3.class);
    when(mockedS3Client.doesBucketExistV2(any(String.class))).thenReturn(false);
    fs.setCustomClient(mockedS3Client);
    fs.getUnknownContainer("testunknown");
  }

  @Test(expected = ContainerAccessDeniedException.class)
  public void testUnknownContainerExistsButNoPermissions() throws IOException {
    TestExtendedS3FileSystem fs = new TestExtendedS3FileSystem();
    AmazonS3 mockedS3Client = mock(AmazonS3.class);
    when(mockedS3Client.doesBucketExistV2(any(String.class))).thenReturn(true);
    when(mockedS3Client.listObjectsV2(any(ListObjectsV2Request.class)))
        .thenThrow(
            new AmazonS3Exception(
                "Access Denied (Service: Amazon S3; Status Code: 403; Error Code: AccessDenied; Request ID: FF025EBC3B2BF017; S3 Extended Request ID: 9cbmmg2cbPG7+3mXBizXNJ1haZ/0FUhztplqsm/dJPJB32okQRAhRWVWyqakJrKjCNVqzT57IZU=), S3 Extended Request ID: 9cbmmg2cbPG7+3mXBizXNJ1haZ/0FUhztplqsm/dJPJB32okQRAhRWVWyqakJrKjCNVqzT57IZU="));
    fs.setCustomClient(mockedS3Client);
    fs.getUnknownContainer("testunknown");
  }

  @Test
  public void testUnknownContainerWithoutBucketDiscovery() throws IOException {
    TestExtendedS3FileSystem fs = new TestExtendedS3FileSystem();
    AmazonS3 mockedS3Client = mock(AmazonS3.class);
    when(mockedS3Client.doesBucketExistV2(any(String.class))).thenReturn(true);
    fs.setCustomClient(mockedS3Client);
    Configuration confWithBucketDiscovery = new Configuration();
    confWithBucketDiscovery.setBoolean(DREMIO_ENABLE_BUCKET_DISCOVERY, false);
    confWithBucketDiscovery.set(
        Constants.AWS_CREDENTIALS_PROVIDER, AnonymousAWSCredentialsProvider.NAME);
    fs.setup(confWithBucketDiscovery);
    assertDoesNotThrow(
        () -> {
          fs.getUnknownContainer("testunknown");
        });
  }

  @Test
  public void testS3ForbiddenMessage() {
    TestExtendedS3FileSystem fs = new TestExtendedS3FileSystem();
    AmazonS3Exception accessDenied = new AmazonS3Exception("Access Denied");
    accessDenied.setErrorCode("AccessDenied");
    accessDenied.setStatusCode(403);
    String accessDeniedMessage = fs.translateForbiddenMessage("testBucket", accessDenied);
    Assert.assertTrue(accessDeniedMessage.contains("Access Denied"));
    AmazonS3Exception accountProblem =
        new AmazonS3Exception(
            "There is a problem with your AWS account that prevents the operation from completing successfully");
    accountProblem.setErrorCode("AccountProblem");
    accountProblem.setStatusCode(403);
    String accountProblemMessage = fs.translateForbiddenMessage("testBucket", accountProblem);
    Assert.assertTrue(accountProblemMessage.contains("There is a problem with your AWS account"));
    AmazonS3Exception aclError = new AmazonS3Exception("The bucket does not allow ACLs");
    aclError.setErrorCode("AccessControlListNotSupported");
    aclError.setStatusCode(400);
    String aclErrorMessage = fs.translateForbiddenMessage("testBucket", aclError);
    Assert.assertTrue(aclErrorMessage.contains("The bucket does not allow ACLs"));
  }

  @Test
  public void testVerifyCredentialsRetry() {
    StsClient mockedClient = mock(StsClient.class);
    StsClientBuilder mockedClientBuilder = mock(StsClientBuilder.class);
    when(mockedClientBuilder.credentialsProvider(any(AwsCredentialsProvider.class)))
        .thenReturn(mockedClientBuilder);
    when(mockedClientBuilder.region(any(Region.class))).thenReturn(mockedClientBuilder);
    when(mockedClientBuilder.build()).thenReturn(mockedClient);

    TestExtendedS3FileSystem fs = new TestExtendedS3FileSystem();
    AtomicInteger retryAttemptNo = new AtomicInteger(1);
    when(mockedClient.getCallerIdentity(any(GetCallerIdentityRequest.class)))
        .then(
            invocationOnMock -> {
              if (retryAttemptNo.incrementAndGet() < 10) {
                throw SdkClientException.builder()
                    .message("Unable to load credentials from service endpoint.")
                    .build();
              }
              return null;
            });

    try (MockedStatic<StsClient> mocked = mockStatic(StsClient.class)) {
      mocked.when(() -> StsClient.builder()).thenReturn(mockedClientBuilder);
      fs.verifyCredentials(new Configuration());
    }
    assertEquals(10, retryAttemptNo.get());
  }

  @Test(expected = RuntimeException.class)
  public void testVerifyCredentialsNoRetryOnAuthnError() {

    StsClient mockedClient = mock(StsClient.class);
    StsClientBuilder mockedClientBuilder = mock(StsClientBuilder.class);
    when(mockedClientBuilder.credentialsProvider(any(AwsCredentialsProvider.class)))
        .thenReturn(mockedClientBuilder);
    when(mockedClientBuilder.region(any(Region.class))).thenReturn(mockedClientBuilder);
    when(mockedClientBuilder.build()).thenReturn(mockedClient);

    TestExtendedS3FileSystem fs = new TestExtendedS3FileSystem();
    AtomicInteger retryAttemptNo = new AtomicInteger(0);
    when(mockedClient.getCallerIdentity(any(GetCallerIdentityRequest.class)))
        .then(
            invocationOnMock -> {
              retryAttemptNo.incrementAndGet();
              throw StsException.builder()
                  .message(
                      "The security token included in the request is invalid. (Service: Sts, Status Code: 403, Request ID: a7e2e92e-5ebb-4343-87a1-21e4d64edcd4)")
                  .build();
            });

    try (MockedStatic<StsClient> mocked = mockStatic(StsClient.class)) {
      mocked.when(() -> StsClient.builder()).thenReturn(mockedClientBuilder);
      fs.verifyCredentials(new Configuration());
    }
    assertEquals(1, retryAttemptNo.get());
  }

  @Test
  public void testIsPathStyleAccessEnabled() {
    Configuration conf = new Configuration();
    Assert.assertEquals(S3FileSystem.isPathStyleAccessEnabled(conf), false);

    conf.setBoolean("fs.s3a.path.style.access.wrongconfigname", true);
    Assert.assertEquals(S3FileSystem.isPathStyleAccessEnabled(conf), false);

    conf.setBoolean(Constants.PATH_STYLE_ACCESS, true);
    Assert.assertEquals(S3FileSystem.isPathStyleAccessEnabled(conf), true);

    conf.setBoolean(Constants.PATH_STYLE_ACCESS, false);
    Assert.assertEquals(S3FileSystem.isPathStyleAccessEnabled(conf), false);
  }

  private AccessControlList getAcl(final AmazonS3 s3Client) {
    ArrayList<Grant> grantCollection = new ArrayList<>();

    // Grant the account owner full control.
    Grant grant1 =
        new Grant(
            new CanonicalGrantee(s3Client.getS3AccountOwner().getId()), Permission.FullControl);
    grantCollection.add(grant1);

    // Save grants by replacing all current ACL grants with the two we just created.
    AccessControlList bucketAcl = new AccessControlList();
    bucketAcl.grantAllPermissions(grantCollection.toArray(new Grant[0]));
    return bucketAcl;
  }

  public static class TestExtendS3AFileSystem extends S3AFileSystem {
    private final String bucketName;
    private boolean fsWasClosed;

    public TestExtendS3AFileSystem(String bucketName) {
      this.bucketName = bucketName;
      fsWasClosed = false;
    }

    @Override
    public void close() throws IOException {
      fsWasClosed = true;
    }

    @Override
    public FileStatus getFileStatus(Path f) throws IOException {
      return new FileStatus(0, false, 0, 0, 0, new Path("/" + bucketName));
    }

    public boolean wasFSClosed() {
      return fsWasClosed;
    }
  }

  private static final class TestExtendedS3FileSystem extends S3FileSystem {
    private AmazonS3 s3;

    void setCustomClient(AmazonS3 s3) {
      this.s3 = s3;
    }

    @Override
    protected CloseableResource<AmazonS3> getS3V1Client() throws Exception {
      return new CloseableResource(s3, s3 -> {});
    }

    @Override
    public AwsCredentialsProvider getAsync2Provider(Configuration conf) {
      AwsCredentialsProvider mockProvider = mock(AwsCredentialsProvider.class);
      return mockProvider;
    }

    @Override
    protected boolean isRequesterPays() {
      return false;
    }
  }
}
