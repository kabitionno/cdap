/*
 * Copyright © 2016-2018 Cask Data, Inc.
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

import DataSourceConfigurer from 'services/datasource/DataSourceConfigurer';
import MetricsQueryHelper from 'services/MetricsQueryHelper';
import {MyMetricApi} from 'api/metric';
import PipelineDetailStore from 'components/PipelineDetails/store';
import PipelineMetricsStore, {PipelineMetricsActions} from 'services/PipelineMetricsStore';

let dataSrc = DataSourceConfigurer.getInstance();
let metricsPoll;

const requestForMetrics = (params) => {
  getMetrics(params, 'REQUEST');
};

const pollForMetrics = (params) => {
  if (metricsPoll) {
    metricsPoll.unsubscribe();
  }
  metricsPoll = getMetrics(params, 'POLL');
  return metricsPoll;
};

function getMetrics(params, type) {
  let searchApi;
  if (type === 'REQUEST') {
    searchApi = dataSrc.request.bind(dataSrc);
  } else if (type === 'POLL') {
    searchApi = dataSrc.poll.bind(dataSrc);
  }

  let metricParams = MetricsQueryHelper.tagsToParams(params);
  let metricSearchPath = '/metrics/search?target=metric&' + metricParams;
  let metricsSearchReqObj = {
    _cdapPath: metricSearchPath,
    method: 'POST'
  };
  return searchApi(metricsSearchReqObj)
    .subscribe(res => {
      let config = PipelineDetailStore.getState().config;
      let stagesArray, source, sinks, transforms;
      if (config.stages) {
        stagesArray = config.stages.map(n => n.name);
      } else {
        source = config.source.name;
        transforms = config.transforms.map(function (n) { return n.name; });
        sinks = config.sinks.map(function (n) { return n.name; });
        stagesArray = [source].concat(transforms, sinks);
      }
      let metricQuery = ['system.app.log.error', 'system.app.log.warn'];

      if (res.length > 0) {
        stagesArray.forEach(stage => {
          // Prefixing it with user. as we to filter out only user metrics and not system metrics
          // This was a problem if a node name is a substring of a system metric. Ref: CDAP-12121
          let stageMetrics = res.filter(metric => metric.indexOf(`user.${stage}`) !== -1);
          metricQuery = metricQuery.concat(stageMetrics);
        });

        const postBody = {
          qid: {
            tags: params,
            metrics: metricQuery,
            aggregate: true,
            timeRange: {
              startTime: 0,
              endTime: 'now'
            }
          }
        };

        MyMetricApi.query(null, postBody)
          .subscribe(metrics => {
            parseMetrics(metrics.qid);
          });
      }
    });
}

const parseMetrics = (metrics) => {
  const systemLogMetrics = [
    'system.app.log.error',
    'system.app.log.warn'
  ];
  let metricObj = {};
  let logsMetrics = {};
  metrics.series.forEach(function (metric) {
    let split = metric.metricName.split('.');
    let key = split[1];

    if (key !== 'app' && !metricObj[key]) {
      metricObj[key] = {
        nodeName: key
      };
    }

    let metricName = metric.metricName;
    let metricValue = metric.data[0].value;

    if (metricName.indexOf(key + '.records.in') !== -1) {
      metricObj[key].recordsIn = metricValue;
    } else if (metricName.indexOf(key + '.records.out') !== -1) {

      // contains multiple records.out metrics
      if (metricName.indexOf(key + '.records.out.') !== -1) {
        let port = split[split.length - 1];
        if (!metricObj[key].recordsOut) {
          metricObj[key].recordsOut = {};
        }
        metricObj[key].recordsOut[port] = metricValue;
      } else {
        metricObj[key].recordsOut = metricValue;
      }

    } else if (metricName.indexOf(key + '.records.error') !== -1) {
      metricObj[key].recordsError = metricValue;
    } else if (systemLogMetrics.indexOf(metricName) !== -1) {
      logsMetrics[metricName] = metricValue;
    }
  });

  PipelineMetricsStore.dispatch({
    type: PipelineMetricsActions.SET_METRICS,
    payload: { metrics: Object.values(metricObj) }
  });
  PipelineMetricsStore.dispatch({
    type: PipelineMetricsActions.SET_LOGS_METRICS,
    payload: { logsMetrics }
  });
};

const setMetricsTabActive = (metricsTabActive, portsToShow) => {
  PipelineMetricsStore.dispatch({
    type: PipelineMetricsActions.SET_ACTIVE_TAB,
    payload: {
      metricsTabActive,
      portsToShow
    }
  });
};

const reset = () => {
  if (metricsPoll) {
    metricsPoll.unsubscribe();
  }
  metricsPoll = null;
  PipelineMetricsStore.dispatch({
    type: PipelineMetricsActions.RESET
  });
};

export {
  requestForMetrics,
  pollForMetrics,
  setMetricsTabActive,
  reset
};
