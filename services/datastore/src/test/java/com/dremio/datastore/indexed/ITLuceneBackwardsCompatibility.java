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
package com.dremio.datastore.indexed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.google.common.collect.ImmutableList;
import java.util.List;
import org.apache.lucene.codecs.Codec;
import org.junit.Test;

/**
 * Test for the presence of previous versions of the Lucene codecs used by Dremio. Old codecs are
 * necessary during Dremio upgrade tasks to read old Lucene indices.
 */
public class ITLuceneBackwardsCompatibility {

  private static final List<String> CODEC_NAMES =
      ImmutableList.of(
          "Lucene62", // Dremio < 22.0
          "Lucene70" // Dremio 22.0+
          // Add new codec(s) here when updating to a new version of Lucene
          );

  /** Check that all codecs present in the list can be instantiated */
  @Test
  public void checkCodecsArePresent() {
    assertThat(CODEC_NAMES)
        .allSatisfy(
            codec -> {
              assertThatNoException()
                  .as("Missing codec '%s'", codec)
                  .isThrownBy(() -> Codec.forName(codec));
            });
  }

  /**
   * Check that the current codec used by Lucene is in the list. If this test fails, it means that
   * you updated Lucene but didn't update the list of codecs above!
   */
  @Test
  public void checkCurrentCodecInList() {
    final String defaultCodecName = Codec.getDefault().getName();
    assertThat(CODEC_NAMES)
        .as("Codec '%s' is missing from CODEC_NAMES", defaultCodecName)
        .contains(defaultCodecName);
  }
}
