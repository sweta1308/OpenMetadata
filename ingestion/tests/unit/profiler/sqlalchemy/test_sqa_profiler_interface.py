#  Copyright 2025 Collate
#  Licensed under the Collate Community License, Version 1.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#  https://github.com/open-metadata/OpenMetadata/blob/main/ingestion/LICENSE
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.

"""
Test SQA Interface
"""

import os
from datetime import datetime
from unittest import TestCase
from unittest.mock import patch
from uuid import uuid4

from sqlalchemy import TEXT, Column, Integer, String, inspect
from sqlalchemy.orm import declarative_base
from sqlalchemy.orm.session import Session

from metadata.generated.schema.api.data.createTableProfile import (
    CreateTableProfileRequest,
)
from metadata.generated.schema.entity.data.table import Column as EntityColumn
from metadata.generated.schema.entity.data.table import (
    ColumnName,
    ColumnProfile,
    DataType,
    Table,
    TableProfile,
)
from metadata.generated.schema.entity.services.connections.database.sqliteConnection import (
    SQLiteConnection,
    SQLiteScheme,
)
from metadata.generated.schema.type.basic import Timestamp
from metadata.profiler.api.models import ThreadPoolMetrics
from metadata.profiler.interface.sqlalchemy.profiler_interface import (
    SQAProfilerInterface,
)
from metadata.profiler.metrics.core import (
    ComposedMetric,
    MetricTypes,
    QueryMetric,
    StaticMetric,
)
from metadata.profiler.metrics.static.row_count import RowCount
from metadata.profiler.processor.default import get_default_metrics
from metadata.sampler.sqlalchemy.sampler import SQASampler


class User(declarative_base()):
    __tablename__ = "users"
    id = Column(Integer, primary_key=True)
    name = Column(String(256))
    fullname = Column(String(256))
    nickname = Column(String(256))
    comments = Column(TEXT)
    age = Column(Integer)


class SQAInterfaceTest(TestCase):
    def setUp(self) -> None:
        table_entity = Table(
            id=uuid4(),
            name="user",
            columns=[
                EntityColumn(
                    name=ColumnName("id"),
                    dataType=DataType.INT,
                )
            ],
        )
        sqlite_conn = SQLiteConnection(
            scheme=SQLiteScheme.sqlite_pysqlite,
        )

        with patch.object(SQASampler, "build_table_orm", return_value=User):
            sampler = SQASampler(
                service_connection_config=sqlite_conn,
                ometa_client=None,
                entity=None,
            )

        with patch.object(SQASampler, "build_table_orm", return_value=User):
            self.sqa_profiler_interface = SQAProfilerInterface(
                sqlite_conn, None, table_entity, None, sampler, 5, 43200
            )
        self.table = User

    def test_init_interface(self):
        """Test we can instantiate our interface object correctly"""

        assert isinstance(self.sqa_profiler_interface.session, Session)

    def tearDown(self) -> None:
        self.sqa_profiler_interface._sampler = None


class SQAInterfaceTestMultiThread(TestCase):
    table_entity = Table(
        id=uuid4(),
        name="user",
        columns=[
            EntityColumn(
                name=ColumnName("id"),
                dataType=DataType.INT,
            )
        ],
    )
    db_path = os.path.join(os.path.dirname(__file__), "test.db")
    sqlite_conn = SQLiteConnection(
        scheme=SQLiteScheme.sqlite_pysqlite,
        databaseMode=db_path + "?check_same_thread=False",
    )

    with patch.object(SQASampler, "build_table_orm", return_value=User):
        sampler = SQASampler(
            service_connection_config=sqlite_conn,
            ometa_client=None,
            entity=None,
        )

    sqa_profiler_interface = SQAProfilerInterface(
        sqlite_conn,
        None,
        table_entity,
        None,
        sampler,
        5,
        43200,
    )

    @classmethod
    def setUpClass(cls) -> None:
        """
        Prepare Ingredients
        """
        User.__table__.create(bind=cls.sqa_profiler_interface.session.get_bind())

        data = [
            User(name="John", fullname="John Doe", nickname="johnny b goode", age=30),
            User(name="Jane", fullname="Jone Doe", nickname=None, age=31),
        ]
        cls.sqa_profiler_interface.session.add_all(data)
        cls.sqa_profiler_interface.session.commit()
        cls.table = User
        cls.metrics = get_default_metrics(cls.table)
        cls.static_metrics = [
            metric for metric in cls.metrics if issubclass(metric, StaticMetric)
        ]
        cls.composed_metrics = [
            metric for metric in cls.metrics if issubclass(metric, ComposedMetric)
        ]
        cls.window_metrics = [
            metric
            for metric in cls.metrics
            if issubclass(metric, StaticMetric) and metric.is_window_metric()
        ]
        cls.query_metrics = [
            metric
            for metric in cls.metrics
            if issubclass(metric, QueryMetric) and metric.is_col_metric()
        ]

    def test_init_interface(self):
        """Test we can instantiate our interface object correctly"""

        assert isinstance(self.sqa_profiler_interface.session, Session)

    def test_get_all_metrics(self):
        table_metrics = [
            ThreadPoolMetrics(
                metrics=[
                    metric
                    for metric in self.metrics
                    if (not metric.is_col_metric() and not metric.is_system_metrics())
                ],
                metric_type=MetricTypes.Table,
                column=None,
                table=self.table,
            )
        ]
        column_metrics = []
        query_metrics = []
        window_metrics = []
        for col in inspect(User).c:
            column_metrics.append(
                ThreadPoolMetrics(
                    metrics=[
                        metric
                        for metric in self.static_metrics
                        if metric.is_col_metric() and not metric.is_window_metric()
                    ],
                    metric_type=MetricTypes.Static,
                    column=col,
                    table=self.table,
                )
            )
            for query_metric in self.query_metrics:
                query_metrics.append(
                    ThreadPoolMetrics(
                        metrics=query_metric,
                        metric_type=MetricTypes.Query,
                        column=col,
                        table=self.table,
                    )
                )
            window_metrics.append(
                ThreadPoolMetrics(
                    metrics=[
                        metric
                        for metric in self.window_metrics
                        if metric.is_window_metric()
                    ],
                    metric_type=MetricTypes.Window,
                    column=col,
                    table=self.table,
                )
            )

        all_metrics = [*table_metrics, *column_metrics, *query_metrics, *window_metrics]

        profile_results = self.sqa_profiler_interface.get_all_metrics(
            all_metrics,
        )

        column_profile = [
            ColumnProfile(**profile_results["columns"].get(col.name))
            for col in inspect(User).c
            if profile_results["columns"].get(col.name)
        ]

        table_profile = TableProfile(
            columnCount=profile_results["table"].get("columnCount"),
            rowCount=profile_results["table"].get(RowCount.name()),
            timestamp=Timestamp(int(datetime.now().timestamp())),
        )

        profile_request = CreateTableProfileRequest(
            tableProfile=table_profile, columnProfile=column_profile
        )

        assert profile_request.tableProfile.columnCount == 6
        assert profile_request.tableProfile.rowCount == 2
        name_column_profile = [
            profile
            for profile in profile_request.columnProfile
            if profile.name == "name"
        ][0]
        id_column_profile = [
            profile for profile in profile_request.columnProfile if profile.name == "id"
        ][0]
        assert name_column_profile.nullCount == 0
        assert id_column_profile.median == 1.0

    @classmethod
    def tearDownClass(cls) -> None:
        os.remove(cls.db_path)
        return super().tearDownClass()
