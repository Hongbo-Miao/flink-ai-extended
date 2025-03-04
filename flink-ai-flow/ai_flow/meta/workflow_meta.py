#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#
import cloudpickle

from ai_flow.api.context_extractor import ContextExtractor

from ai_flow.util.json_utils import Jsonable
from typing import Text, List
from ai_flow.common.properties import Properties
from ai_flow.workflow.control_edge import WorkflowSchedulingRule, WorkflowAction, EventCondition


class WorkflowMeta(Jsonable):
    """define workflow meta"""

    def __init__(self,
                 name: Text,
                 project_id: int,
                 properties: Properties = None,
                 create_time: int = None,
                 update_time: int = None,
                 context_extractor_in_bytes: bytes = None,
                 graph: str = None,
                 uuid: int = None,
                 scheduling_rules: List[WorkflowSchedulingRule] = None
                 ) -> None:
        """

        :param name: the workflow name
        :param project_id: the uuid of project which contains this workflow
        :param properties: the workflow properties
        :param create_time: create time represented as milliseconds since epoch.
        :param update_time: update time represented as milliseconds since epoch.
        :param context_extractor_in_bytes: the user-defined logic of how to extract context from event
        :param graph: the graph of the workflow
        :param uuid: uuid in database
        :param scheduling_rules: A list of scheduling rules of the workflow.
        """

        self.name = name
        self.project_id = project_id
        self.properties = properties
        self.create_time = create_time
        self.update_time = update_time
        self.uuid = uuid
        self.context_extractor_in_bytes = context_extractor_in_bytes
        self.graph = graph
        self.scheduling_rules = [] if scheduling_rules is None else scheduling_rules

    def get_condition(self, action: WorkflowAction) -> List[EventCondition]:
        if self.scheduling_rules is None:
            return []
        return [rule.event_condition for rule in self.scheduling_rules if rule.action == action]

    def update_condition(self, event_conditions: List[EventCondition], action: WorkflowAction):
        scheduling_rules = self.scheduling_rules if self.scheduling_rules is not None else []
        new_scheduling_rules = [rule for rule in scheduling_rules if rule.action != action]
        new_scheduling_rules.extend(
            [WorkflowSchedulingRule(condition, action) for condition in event_conditions])
        self.scheduling_rules = new_scheduling_rules

    def __str__(self):
        return '<\n' \
               'WorkflowMeta\n' \
               'uuid:{},\n' \
               'name:{},\n' \
               'project_id:{},\n' \
               'properties:{},\n' \
               'create_time:{},\n' \
               'update_time:{},\n' \
               'context_extractor_in_bytes:{},\n' \
               'graph:{},\n' \
               '>'.format(self.uuid,
                          self.name,
                          self.project_id,
                          self.properties,
                          self.create_time,
                          self.update_time,
                          self.context_extractor_in_bytes,
                          self.graph)

    def get_context_extractor(self) -> ContextExtractor:
        """
        Return the deserialized ContextExtractor instance.

        """
        return cloudpickle.loads(self.context_extractor_in_bytes)

    def set_context_extractor(self, context_extractor: ContextExtractor):
        """
        Set the context_extractor_in_bytes from given ContextExtractor instance.

        :param context_extractor: ContextExtractor instance
        """
        self.context_extractor_in_bytes = cloudpickle.dumps(context_extractor)


def create_workflow(name: Text,
                    project_id: int,
                    properties: Properties = None,
                    create_time: int = None,
                    update_time: int = None,
                    context_extractor_in_bytes: bytes = None,
                    graph: str = None
                    ) -> WorkflowMeta:
    return WorkflowMeta(name=name, project_id=int(project_id), properties=properties,
                        create_time=create_time, update_time=update_time,
                        context_extractor_in_bytes=context_extractor_in_bytes,
                        graph=graph)
