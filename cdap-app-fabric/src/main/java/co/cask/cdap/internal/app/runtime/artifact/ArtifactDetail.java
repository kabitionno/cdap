/*
 * Copyright © 2015 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.internal.app.runtime.artifact;

/**
 * Details about an artifact, including info about the artifact itself and metadata about the contents of the artifact.
 */
public class ArtifactDetail {
  private final ArtifactInfo info;
  private final ArtifactMeta meta;

  public ArtifactDetail(ArtifactInfo info, ArtifactMeta meta) {
    this.info = info;
    this.meta = meta;
  }

  public ArtifactInfo getInfo() {
    return info;
  }

  public ArtifactMeta getMeta() {
    return meta;
  }

}
