<#--

    Copyright (C) 2017-2019 Dremio Corporation

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

-->

<#include "*/generic.ftl">
<#macro page_head>
<script src="/static/js/d3.v3.js"></script>
<script src="/static/js/dagre-d3.min.js"></script>
<script src="/static/js/graph.js"></script>
</#macro>

<#macro page_body>
<div id="globalconfig" style="position:absolute;display:none;">
{
  "queryid" : "${model.getQueryId()}",
  "operators" : ${model.getOperatorsJSON()?no_esc},
  "planText": "${model.getPlanText()}",
  "fragmentProfileSize": ${model.getFragmentProfilesSize()},
  "fragmentProfiles":  ${model.getFragmentsJSON()?no_esc},
  "operatorProfiles":   ${model.getOperatorProfilesJSON()?no_esc},
  <#-- Do not remove "state". Ref : DX-98143 -->
  "state": "${model.getStateName()}"
}
</div>
  <h3>Query and Planning</h3>
  <ul id="query-tabs" class="nav nav-tabs" role="tablist">
    <li><a href="#query-query" role="tab" data-toggle="tab">Query</a></li>
    <li><a href="#query-visual" role="tab" data-toggle="tab">Visualized Plan</a></li>
    <li><a href="#query-plan" role="tab" data-toggle="tab">Planning</a></li>
    <li><a href="#query-acceleration" role="tab" data-toggle="tab">Acceleration</a></li>
    <#if model.hasError() ><li><a href="#query-error" role="tab" data-toggle="tab">Error</a></li></#if>
  </ul>
  <div id="query-content" class="tab-content">
    <div id="query-query" class="tab-pane">
      <p><pre>${model.profile.query}</pre></p>

      <h3>Job Summary</h3>
      <dl class="dl-horizontal info-list">
        <dt>State:</dt>
        <dd>${model.getStateName()}</dd>
        <dt>Coordinator:</dt>
        <dd>${model.getProfile().getForeman().getAddress()}</dd>
        <dt>Threads:</dt>
        <dd>${model.getProfile().getTotalFragments()}</dd>
        <dt>Command Pool Wait:</dt>
        <dd>${model.getCommandPoolWaitMillis()}</dd>
        <dt>Total Query Time:</dt>
        <dd>${model.getTotalTime()}</dd>
        <#if model.profile.hasNumJoinsInUserQuery() >
          <dt># Joins in user query:</dt>
          <dd>${model.profile.getNumJoinsInUserQuery()}</dd>
        </#if>
        <#if model.profile.hasNumJoinsInFinalPrel() >
          <dt># Joins in final plan:</dt>
          <dd>${model.profile.getNumJoinsInFinalPrel()}</dd>
        </#if>
        <dt>Considered Reflections:</dt>
        <dd>${model.getConsideredReflectionsCount()}</dd>
        <dt>Matched Reflections:</dt>
        <dd>${model.getMatchedReflectionsCount()}</dd>
        <dt>Chosen Reflections:</dt>
        <dd>${model.getChosenReflectionsCount()}</dd>
        <#if model.getEarliestReflectionRefresh()?? >
          <dt>Earliest Reflection Refresh:</dt>
          <dd>${model.getEarliestReflectionRefresh()?number_to_datetime?iso_local}</dd>
        </#if>
        <#if model.getPlanCacheUsed() != 0 >
          <dt>Cached plan was used</dt>
          <dd> </dd>
        </#if>
        <#if model.getResultsCacheUsed() >
          <dt>Cached result was used</dt>
          <dd> </dd>
        </#if>
      </dl>
      <h3>Time in UTC</h3>
      <dl class="dl-horizontal info-list">
        <dt>Start time:</dt>
        <dd>${model.getStartTimeInUTC()}</dd>
        <dt>End time:</dt>
        <dd>${model.getEndTimeInUTC()}</dd>
        <dt>Cancel Start time:</dt>
        <dd>${model.getCancelStartTimeInUTC()}</dd>
      </dl>

      <h3>State Durations</h3>
        <dl class="dl-horizontal info-list">
          <dt>Pending:</dt>
          <dd>${model.getPendingTime()}</dd>
          <dt>Metadata Retrieval:</dt>
          <dd>${model.getMetadataRetrievalTime()}</dd>
          <dt>Planning:</dt>
          <dd>${model.getPlanningTime()}</dd>
          <dt>Engine Start:</dt>
          <dd>${model.getEngineStartTime()}</dd>
          <dt>Queued:</dt>
          <dd>${model.getQueuedTime()}</dd>
          <dt>Execution Planning:</dt>
          <dd>${model.getExecutionPlanningTime()}</dd>
          <dt>Starting:</dt>
          <dd>${model.getStartingTime()}</dd>
          <dt>Running:</dt>
          <dd>${model.getRunningTime()}</dd>
        </dl>

      <h3>Context </h3>
        <dd>${model.profile.getContextInfo().getSchemaPathContext()}</dd>
        <#if model.profile.getContextInfo().getSourceVersionSettingCount() != 0  >
         <div class="panel-group" id="source-version-accordion">
          <div class="panel panel-default">
           <div class="panel-heading">
            <h4 class="panel-title">
              <a data-toggle="collapse" href="#source-version-overview">
                Source-Version Mapping
              </a>
            </h4>
           </div>
           <div id="source-version-overview" class="panel-collapse collapse">
            <div class="panel-body">
              ${model.getSourceVersionMapping()?no_esc}
            </div>
           </div>
          </div>
         </div>
       </#if>

      <h3>Threads</h3>
      <div class="panel-group" id="fragment-accordion">
        <div class="panel panel-default">
          <div class="panel-heading">
            <h4 class="panel-title">
              <a data-toggle="collapse" href="#fragment-overview">
                Overview
              </a>
            </h4>
          </div>
          <div id="fragment-overview" class="panel-collapse collapse">
            <div class="panel-body">
              ${model.getFragmentsOverview()?no_esc}
            </div>
          </div>
        </div>
        <#list model.getFragmentProfiles() as frag>
          <div class="panel panel-default">
            <div class="panel-heading" onclick="toggleFragment('${frag.getId()}')">
              <h4 class="panel-title">
                <a data-toggle="collapse" href="#${frag.getId()}" class="collapsed">
                  ${frag.getDisplayName()}
                </a>
              </h4>
            </div>
            <div id="${frag.getId()}" class="panel-collapse collapse">
              <div class="panel-body">
                <div class="fragment-table"></div>
                <div class="panel panel-default">
                  <div class="panel-heading" onclick="toggleFragmentMetrics('${frag.getId()}')">
                    <h4 class="panel-title">
                      <a data-toggle="collapse" href="#${frag.getId()}-metrics" class="collapsed">
                        Phase Metrics
                      </a>
                    </h4>
                  </div>
                  <div id="${frag.getId()}-metrics" class="panel-collapse collapse">
                    <div class="panel-body">
                      <div class="metrics-table"></div>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </#list>
      </div>

      <h3>Resource Allocation</h3>
      <div class="panel-group" id="resource-accordion">
        <div class="panel panel-default">
          <div class="panel-heading">
            <h4 class="panel-title">
              <a data-toggle="collapse" href="#resource-allocation-overview">
                Overview
              </a>
            </h4>
          </div>
          <div id="resource-allocation-overview" class="panel-collapse collapse">
            <div class="panel-body">
              ${model.getResourceSchedulingOverview()?no_esc}
            </div>
          </div>
        </div>
      </div>

      <h3>Nodes</h3>
      <div class="panel-group" id="node-accordion">
        <div class="panel panel-default">
          <div class="panel-heading">
            <h4 class="panel-title">
              <a data-toggle="collapse" href="#node-overview">
                Overview
              </a>
            </h4>
          </div>
          <div id="node-overview" class="panel-collapse collapse">
            <div class="panel-body">
              ${model.getNodesOverview()?no_esc}
            </div>
          </div>
        </div>
      </div>

      <h3>Operators</h3>

      <div class="panel-group" id="operator-accordion">
        <div class="panel panel-default">
          <div class="panel-heading">
            <h4 class="panel-title">
              <a data-toggle="collapse" href="#operator-overview">
                Overview
              </a>
            </h4>
          </div>
          <div id="operator-overview" class="panel-collapse collapse">
            <div class="panel-body">
              ${model.getOperatorsOverview()?no_esc}
            </div>
          </div>
        </div>

        <#list model.getOperatorProfiles() as op>
        <div class="panel panel-default">
          <div class="panel-heading" onclick="toggleOperator('${op.getId()}')">
            <h4 class="panel-title">
              <a data-toggle="collapse" href="#${op.getId()}" class="collapsed">
                ${op.getDisplayName()}
              </a>
            </h4>
          </div>
          <div id="${op.getId()}" class="panel-collapse collapse">
            <div class="panel-body">
              <div class="operator-table"></div>
              <div class="panel panel-default">
                <div class="panel-heading" onclick="toggleOperatorMetrics('${op.getId()}')">
                  <h4 class="panel-title">
                    <a data-toggle="collapse" href="#${op.getId()}-metrics" class="collapsed">
                      Operator Metrics
                    </a>
                  </h4>
                </div>
                <div id="${op.getId()}-metrics" class="panel-collapse collapse">
                  <div class="panel-body">
                    <div class="metrics-table"></div>
                  </div>
                </div>
              </div>
              <div class="panel panel-default">
                <div class="panel-heading" onclick="toggleOperatorDetails('${op.getId()}')">
                  <h4 class="panel-title">
                    <a data-toggle="collapse" href="#${op.getId()}-details" class="collapsed">
                      Operator Details
                    </a>
                  </h4>
                </div>
                <div id="${op.getId()}-details" class="panel-collapse collapse">
                  <div class="panel-body">
                    <div class="details-table"></div>
                  </div>
                </div>
               </div>

              <div class="panel panel-default">
                <div class="panel-heading" onclick="toggleHostMetrics('${op.getId()}')">
                  <h4 class="panel-title">
                    <a data-toggle="collapse" href="#${op.getId()}-hostMetrics" class="collapsed">
                      Host Metrics
                    </a>
                  </h4>
                </div>
                <div id="${op.getId()}-hostMetrics" class="panel-collapse collapse">
                  <div class="panel-body">
                    <div class="hostMetrics-table"></div>
                  </div>
                </div>
               </div>

            </div>
          </div>
        </div>
        </#list>
      </div>

    </div>
    <div id="query-physical" class="tab-pane">
      <p><pre>${model.profile.plan}</pre></p>
    </div>
    <div id="query-visual" class="tab-pane">
      <svg id="query-visual-canvas" class="center-block"></svg>
    </div>
    <div id="query-acceleration" class="tab-pane">
      <h3>Reflection Outcome</h3>
      <#if model.profile.hasAccelerationProfile() && model.profile.getAccelerationProfile().getAccelerated()>
        <p>Query was accelerated</p>
      <#else>
        <p>Query was NOT accelerated</p>
      </#if>
      <#if model.accelerationDetails.hasErrors()>
        <h4>Substitution Info</h4>
        <ul>
          <#assign errorList = model.accelerationDetails.errors>
          <#list errorList as error>
            <li>
              <p>
              <pre>
                ${error?trim}
              </pre>
              </p>
            </li>
          </#list>
        </ul>
      </#if> <#-- if model.accelerationDetails.hasErrors() -->
      <#if model.profile.hasAccelerationProfile() && model.profile.getAccelerationProfile().getLayoutProfilesCount() != 0>
        <#if model.accelerationDetails??>
        <ul>
          <#assign layoutList = model.getDatasetGroupedLayoutList()>
          <#list layoutList as k, v>
            <#assign dsName = k.getDataset().getName()>
            <#assign dsPath = k.toParentPath()>
            <#assign dsVersion = k.getVersion()>
            <li>${dsName}${dsVersion}(${dsPath})</li>
            <ul>
              <#list v as layout>
                <#if layout.name?? && layout.name?trim?has_content >
                  <#assign layoutName = layout.name>
                <#else>
                  <#assign layoutName = layout.layoutId>
                </#if>
                <li>${layoutName} (Type: <#if layout.displayColumnsList?has_content>raw<#else>agg</#if>, Refection Id: ${layout.layoutId}): considered<#if layout.numSubstitutions != 0>, matched<#if layout.numUsed != 0>, chosen<#else>, not chosen</#if><#else>, not matched</#if>.</li>
              </#list>
            </ul>
          </#list>
        </ul>
        <#else>
        <ul>
          <#list model.profile.getAccelerationProfile().getLayoutProfilesList() as layout>
            <#if layout.name?? && layout.name?trim?has_content >
              <#assign layoutName = layout.name>
            <#else>
              <#assign layoutName = layout.layoutId>
            </#if>
            <#if layout.type??>
              <#assign reflectionType = layout.type?lower_case>
            <#else>
              <#if layout.displayColumnsList?has_content>
                <#assign reflectionType = raw>
              <#else>
                <#assign reflectionType = agg>
              </#if>
            </#if>
            <li>${layoutName} (Type: ${reflectionType}, Refection Id: ${layout.layoutId}): considered<#if layout.numSubstitutions != 0>, matched<#if layout.numUsed != 0>, chosen<#else>, not chosen</#if><#else>, not matched</#if>.</li>
          </#list>
        </ul>
        </#if>
      </#if>

      <#if model.profile.hasAccelerationProfile()>
        <p>
        Time To Find Materializations:   ${model.getProfile().getAccelerationProfile().getMillisTakenGettingMaterializations()} ms
        <br>
        Time To Normalize User Query Alternatives and Materializations:   ${model.getProfile().getAccelerationProfile().getMillisTakenNormalizing()} ms
        <br>
        Time To Generate Replacements:   ${model.getProfile().getAccelerationProfile().getMillisTakenSubstituting()} ms
        </p>
      <#else>
        <p>
        Time To Find Materializations:   --
        <br>
        Time To Normalize User Query Alternatives and Materializations:   --
        <br>
        Time To Generate Replacements:   --
        </p>
      </#if>
      <h3>Normalized User Query Alternatives</h3>
      <#if model.profile.hasAccelerationProfile()>
        <#list model.profile.getAccelerationProfile().getNormalizedQueryPlansList() as normalizedPlan>
          <#if normalizedPlan?has_content >
          <p><pre>${normalizedPlan}</pre></p>
          </#if>
        </#list>
      </#if>
      <h3>Reflection Details</h3>
        <#if model.profile.hasAccelerationProfile() && model.profile.getAccelerationProfile().getLayoutProfilesCount() != 0>
          <#list model.profile.getAccelerationProfile().getLayoutProfilesList() as layout>

          <#if layout.name?? && layout.name?trim?has_content >
          <#assign layoutName = layout.name>
          <#else>
          <#assign layoutName = layout.layoutId>
          </#if>
          <h4>Reflection Definition: ${layoutName}</h4>
          <p>
          Matched:   ${layout.getNumSubstitutions()}, Chosen:  ${layout.getNumUsed()}, Match Latency:   ${layout.getMillisTakenSubstituting()} ms<br>
          </p>
          <p>
          Reflection Id: ${layout.getLayoutId()}, Materialization Id: ${layout.getMaterializationId()}<br>
          Expiration:   ${layout.materializationExpirationTimestamp?number_to_datetime?iso_local}<br>
          <#if model.accelerationDetails?? && model.accelerationDetails.hasRelationship(layout.layoutId) >
          Dataset: ${model.accelerationDetails.getReflectionDatasetPath(layout.layoutId)}${model.accelerationDetails.getReflectionDatasetVersion(layout.layoutId)}<br>
          Last Refresh from Table: <#if model.accelerationDetails.getRefreshChainStartTime(layout.layoutId)?? > ${model.accelerationDetails.getRefreshChainStartTime(layout.layoutId)?number_to_datetime?iso_local}<br></#if>
          </#if>
          <#if layout.isStale?has_content && layout.isStale>
          Stale: yes<br>
          </#if>
          <#if layout.snowflake?has_content && layout.snowflake>
          Snowflake: yes<br>
          </#if>
          <#if layout.defaultReflection?has_content && layout.defaultReflection>
            Default Match: yes<br>
          </#if>
          <#if layout.reflectionMode?has_content >
            Reflection Mode: ${layout.getReflectionMode()}<br>
          </#if>
          <#if layout.dimensionsList?has_content >
          Dimensions:
            <#list layout.getDimensionsList() as dim>
              ${dim},
            </#list>
          <br />
          </#if>

          <!-- Old measures, kept for bc -->
          <#if layout.measuresList?has_content >
          Measures:
            <#list layout.getMeasuresList() as measures>
              ${measures},
            </#list>
          <br />
          </#if>

          <!-- New measures with types -->
          <#if layout.measureColumnsList?has_content >
          Measures:
            <ul>
            <#list layout.getMeasureColumnsList() as measures>
              <li>${measures.getName()} (
                <#list measures.getMeasureTypeList() as meastureTypeList>
                  ${meastureTypeList},
                </#list>
                )</li>
            </#list>
            </ul>
          <br />
          </#if>

          <#if layout.displayColumnsList?has_content >
          Display:
            <#list layout.getDisplayColumnsList() as display>
              ${display},
            </#list>
          <br />
          </#if>

          <#if layout.sortedColumnsList?has_content >
          Sorted:
            <#list layout.getSortedColumnsList() as sorted>
              ${sorted},
            </#list>
          <br />
          </#if>

          <#if layout.partitionedColumnsList?has_content >
          Partitioned:
            <#list layout.getPartitionedColumnsList() as partitioned>
              ${partitioned},
            </#list>
          <br />
          </#if>

          <#if layout.distributionColumnsList?has_content >
          Distributed:
            <#list layout.getDistributionColumnsList() as dist>
              ${dist},
            </#list>
          <br />
          </#if>
          </p>

          <#if layout.plan?has_content >
          <p>Reflection Plan:
            <pre>${layout.getPlan()}</pre>
          </p>
          </#if>

          <p>Normalized Materialization Targets:
            <#list layout.getNormalizedPlansList() as planNorm>
            <#if planNorm?has_content >
              <p><pre>${planNorm}</pre></p>
            </#if>
            </#list>
          </p>


          <#assign reflectionHints = model.getAccelerationDetails().getHintsForLayoutId(layout.layoutId) >
          <#if reflectionHints?? >
          </p> Matching Hints: <#if model.getAccelerationDetails().isHintHiddenforLayoutId(layout.layoutId)>Not Shown (Too Far From Matching)</#if>
            <ul>
              <#list reflectionHints as reflectionHint>
                <li>
                <#switch reflectionHint.explanationType>
                  <#case "DISJOINT_FILTER">
                    Disjoint Filter ${reflectionHint.filter}
                    <#break>
                  <#case "FIELD_MISSING">
                    Missing Field ${reflectionHint.columnName}
                  <#break>
                  <#case "FILTER_OVER_SPECIFIED">
                    Filter Over Specified ${reflectionHint.filter}
                  <#break>
                </#switch>
                </li>
              </#list>

            </ul>
          </#if>
          </p>

          <p>Replacement Plans:
            <#list layout.getSubstitutionsList() as substitution>
              <#if substitution?has_content >
                <#if substitution.info?has_content>
                  <p><pre>${substitution.getInfo()+"\n"+substitution.getPlan()}</pre></p>
                <#else>
                  <p><pre>${substitution.getPlan()}</pre></p>
                </#if>
              </#if>
            </#list>
          </p>

          <p>Best Cost Replacement Plan:
            <#assign optimizedPlan = layout.getOptimizedPlan()>
            <#if optimizedPlan?has_content>
              <p><pre>${optimizedPlan}</pre></p>
            </#if>
          </p>
          </#list>
          <hr />
        <#else>
          <p>No Reflections Were Applicable.</p>
        </#if>
    </div>
    <div id="query-plan" class="tab-pane">
      <#if model.profile.planPhasesCount != 0>
        <#list model.profile.planPhasesList as planPhase>
          <p>
          ${planPhase.getPhaseName()} (${planPhase.getDurationMillis()} ms)<br />
          <#if planPhase.plan?has_content><p><pre>${planPhase.plan}</pre></p></#if>
          <#if planPhase.plannerDump?has_content><p><pre>${planPhase.plannerDump}</pre></p></#if>
          <#if planPhase.hasSizeStats()><p><pre>${planPhase.sizeStats}</pre></p></#if>
          </p>
        </#list>
        <h3>Rule Execution Times</h3>
        <div class="panel-group" id="phases-accordion">
          <div class="panel panel-default">
            <div class="panel-heading">
              <h4 class="panel-title">
                <a data-toggle="collapse" href="#phases-overview">
                  Overview
                </a>
              </h4>
            </div>
            <div id="phases-overview" class="panel-collapse collapse">
              <div class="panel-body">
                ${model.getRulesBreakdownTable()?no_esc}
              </div>
            </div>
          </div>
        </div>
      <#else>
        <p>No planning phase information to show</p>
      </#if>
      <#if model.querySchema?has_content>
        <h3>Query Output Schema</h3>
        <p><pre>${model.querySchema}</pre></p>
      </#if>
      <#if model.nonDefaultOptions?has_content>
        <h3>Non Default Options</h3>
        <p><pre>${model.nonDefaultOptions}</pre></p>
      </#if>
    </div>

    <#if model.hasError() >
    <div id="query-error" class="tab-pane">
      <p>
      <pre>
      ${model.getProfile().error?trim}
      </pre>
      </p>
      <p>Failure node: ${model.getProfile().errorNode}</p>
      <p>Error ID: ${model.getProfile().errorId}</p>
      <p></p><p>Verbose:</p>
      <p><pre>
         ${model.getProfile().verboseError?trim}
      </pre></p>
    </div>
    </#if>
  </div>
  <script src="/static/js/profile/profile.js"></script>
</#macro>

<@page_html/>
