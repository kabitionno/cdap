/*
 * Copyright © 2018 Cask Data, Inc.
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

import PropTypes from 'prop-types';
import React, {Component} from 'react';
import IconSVG from 'components/IconSVG';
import Popover from 'components/Popover';
import ConfirmationModal from 'components/ConfirmationModal';
import {getCurrentNamespace} from 'services/NamespaceStore';
import {MyAppApi} from 'api/app';
import PipelineExportModal from 'components/PipelineDetails/PipelineDetailsTopPanel/PipelineDetailsDetailsActions/PipelineDetailsActionsButton/PipelineExportModal';
require('./PipelineDetailsActionsButton.scss');

const getClonePipelineName = (name) => {
  name = typeof a === 'string' ? name : name.toString();
  let version = name.match(/(_v[\d]*)$/g);
  let existingSuffix; // For cases where pipeline name is of type 'SamplePipeline_v2_v4_v333'
  if (Array.isArray(version)) {
    version = version.pop();
    existingSuffix = version;
    version = version.replace('_v', '');
    version = '_v' + ((!isNaN(parseInt(version, 10)) ? parseInt(version, 10) : 1) + 1);
  } else {
    version = '_v1';
  }
  return name.split(existingSuffix)[0] + version;
};

export default class PipelineDetailsActionsButton extends Component {
  static propTypes = {
    pipelineName: PropTypes.string,
    description: PropTypes.string,
    artifact: PropTypes.object,
    config: PropTypes.object
  };

  state = {
    showExportModal: false,
    showDeleteConfirmationModal: false
  };

  pipelineConfig = {
    name: this.props.pipelineName,
    description: this.props.description,
    artifact: this.props.artifact,
    config: this.props.config
  };

  duplicateConfigAndNavigate = () => {
    let bumpedVersionName = getClonePipelineName(this.props.pipelineName);
    let pipelineConfigWithBumpedVersion = {...this.pipelineConfig, name: bumpedVersionName};
    window.localStorage.setItem(bumpedVersionName, JSON.stringify(pipelineConfigWithBumpedVersion));
    const hydratorLink = window.getHydratorUrl({
      stateName: 'hydrator.create',
      stateParams: {
        namespace: getCurrentNamespace(),
        cloneId: bumpedVersionName,
        artifactType: this.props.artifact.name
      }
    });
    window.location.href = hydratorLink;
  };

  deletePipeline = () => {
    let namespace = getCurrentNamespace();
    let params = {
      namespace,
      appId: this.props.pipelineName
    };
    const pipelinesListLink = window.getHydratorUrl({
      stateName: 'hydrator.list',
      stateParams: {
        namespace
      }
    });

    MyAppApi.delete(params)
      .subscribe(() => {
        this.setState({
          deleteErrMsg: '',
          extendedDeleteErrMsg: ''
        });
        window.location.href = pipelinesListLink;
      }, (err) => {
        this.setState({
          deleteErrMsg: 'There was a problem with the pipeline you were trying to delete',
          extendedDeleteErrMsg: err
        });
      });
  }

  toggleExportModal = () => {
    this.setState({ showExportModal: !this.state.showExportModal });
  }

  toggleDeleteConfirmationModal = () => {
    this.setState({
      showDeleteConfirmationModal: !this.state.showDeleteConfirmationModal,
      deleteErrMsg: '',
      extendedDeleteErrMsg: ''
    });
  }

  renderExportPipelineModal() {
    if (!this.state.showExportModal) {
      return null;
    }

    return (
      <PipelineExportModal
        isOpen={this.state.showExportModal}
        onClose={this.toggleExportModal}
        pipelineConfig={this.pipelineConfig}
      />
    );
  }

  renderDeleteConfirmationModal() {
    if (!this.state.showDeleteConfirmationModal) {
      return null;
    }

    const confirmationElem = () => <div>Are you sure you want to delete <strong><em>{this.props.pipelineName}</em></strong>?</div>;

    return (
      <ConfirmationModal
        headerTitle='Delete Pipeline'
        toggleModal={this.toggleDeleteConfirmationModal}
        confirmationElem={confirmationElem()}
        confirmButtonText={'Delete'}
        confirmFn={this.deletePipeline}
        cancelFn={this.toggleDeleteConfirmationModal}
        isOpen={this.state.showDeleteConfirmationModal}
        isLoading={this.state.loading}
        errorMessage={this.state.deleteErrMsg}
        extendedMessage={this.state.extendedDeleteErrMsg}
      />
    );
  }

  render() {
    const ActionsBtnAndLabel = () => {
      return (
        <div className="btn pipeline-action-btn pipeline-actions-btn">
          <div className="btn-container">
            <IconSVG name="icon-cog-empty" />
            <div className="button-label">
              Actions
            </div>
          </div>
        </div>
      );
    };

    return (
      <div className="pipeline-action-container pipeline-actions-container">
        <Popover
          target={ActionsBtnAndLabel}
          placement="bottom"
          bubbleEvent={false}
          className="pipeline-actions-popper"
        >
          <ul>
            <li onClick={this.duplicateConfigAndNavigate}>
              Duplicate
            </li>
            <li onClick={this.toggleExportModal}>
              Export
            </li>
            <hr />
            <li onClick={this.toggleDeleteConfirmationModal}>
              Delete Pipeline
            </li>
          </ul>
        </Popover>
        {this.renderExportPipelineModal()}
        {this.renderDeleteConfirmationModal()}
      </div>
    );
  }
}
