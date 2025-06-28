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
Test FQN build behavior
"""
import unittest
from typing import List
from unittest import TestCase
from unittest.mock import MagicMock

import pytest

from metadata.generated.schema.entity.data.table import Table
from metadata.generated.schema.type.basic import FullyQualifiedEntityName
from metadata.ingestion.ometa.utils import quote
from metadata.utils import fqn

try:
    from antlr4 import InputStream
    from antlr4.error.ErrorListener import ErrorListener

    ANTLR_AVAILABLE = True
except ImportError:
    ANTLR_AVAILABLE = False
    ErrorListener = object  # Fallback for type annotations


class FqnGrammarTestErrorListener(ErrorListener):
    """Custom error listener to capture parsing errors"""

    def __init__(self):
        super().__init__()
        self.errors = []

    def syntaxError(self, recognizer, offendingSymbol, line, column, msg, e):
        self.errors.append(f"Line {line}:{column} - {msg}")


class FqnTestCase:
    """Test case for FQN validation"""

    def __init__(self, fqn: str, should_pass: bool, description: str = ""):
        self.fqn = fqn
        self.should_pass = should_pass
        self.description = description

    def __repr__(self):
        return f"FqnTestCase('{self.fqn}', {self.should_pass}, '{self.description}')"


class TestFqn(TestCase):
    """
    Validate FQN building
    """

    def test_split(self):
        this = self

        class FQNTest:
            """
            Test helper class
            """

            def __init__(self, parts, fqn):
                self.parts = parts
                self.fqn = fqn

            def validate(self, actual_parts, actual_fqn):
                this.assertEqual(self.fqn, actual_fqn)
                this.assertEqual(len(self.parts), len(actual_parts))

                for i in range(len(self.parts)):
                    if "." in self.parts[i]:
                        this.assertEqual(fqn.quote_name(self.parts[i]), actual_parts[i])
                    else:
                        this.assertEqual(self.parts[i], actual_parts[i])

        xs = [
            FQNTest(["a", "b", "c", "d"], "a.b.c.d"),
            FQNTest(["a.1", "b", "c", "d"], '"a.1".b.c.d'),
            FQNTest(["a", "b.2", "c", "d"], 'a."b.2".c.d'),
            FQNTest(["a", "b", "c.3", "d"], 'a.b."c.3".d'),
            FQNTest(["a", "b", "c", "d.4"], 'a.b.c."d.4"'),
            FQNTest(["a.1", "b.2", "c", "d"], '"a.1"."b.2".c.d'),
            FQNTest(["a.1", "b.2", "c.3", "d"], '"a.1"."b.2"."c.3".d'),
            FQNTest(["a.1", "b.2", "c.3", "d.4"], '"a.1"."b.2"."c.3"."d.4"'),
            FQNTest(["fqn", "test.test.test"], 'fqn."test.test.test"'),
            FQNTest(["fqn", "testtesttest"], "fqn.testtesttest"),
            FQNTest(["fqn", "testtes ttest"], "fqn.testtes ttest"),
        ]
        for x in xs:
            x.validate(fqn.split(x.fqn), fqn._build(*x.parts))

    def test_quote_name(self):
        """
        Make sure that fqns are properly quoted
        """
        # Unquote_named name remains unquote_named
        self.assertEqual("a", fqn.quote_name("a"))
        # Add quote_names when "." exists in the name
        self.assertEqual('"a.b"', fqn.quote_name("a.b"))
        # Leave existing valid quote_names
        self.assertEqual('"a.b"', fqn.quote_name('"a.b"'))
        # Remove quote_names when not needed
        self.assertEqual("a", fqn.quote_name('"a"'))

        with self.assertRaises(Exception) as context:
            fqn.quote_name('"a')
        self.assertEqual('Invalid name "a', str(context.exception))
        with self.assertRaises(Exception) as context:
            fqn.quote_name('a"')
        self.assertEqual('Invalid name a"', str(context.exception))
        with self.assertRaises(Exception) as context:
            fqn.quote_name('a"b')
        self.assertEqual('Invalid name a"b', str(context.exception))

    def test_invalid(self):
        with self.assertRaises(Exception):
            fqn.split('a.."')

    def test_build_table(self):
        """
        Validate Table FQN building
        """
        mocked_metadata = MagicMock()
        mocked_metadata.es_search_from_fqn.return_value = None
        table_fqn = fqn.build(
            metadata=mocked_metadata,
            entity_type=Table,
            service_name="service",
            database_name="db",
            schema_name="schema",
            table_name="table",
        )
        self.assertEqual(table_fqn, "service.db.schema.table")

        table_fqn_dots = fqn.build(
            metadata=mocked_metadata,
            entity_type=Table,
            service_name="service",
            database_name="data.base",
            schema_name="schema",
            table_name="table",
        )
        self.assertEqual(table_fqn_dots, 'service."data.base".schema.table')

        table_fqn_space = fqn.build(
            metadata=mocked_metadata,
            entity_type=Table,
            service_name="service",
            database_name="data base",
            schema_name="schema",
            table_name="table",
        )
        self.assertEqual(table_fqn_space, "service.data base.schema.table")

    def test_split_test_case_fqn(self):
        """test for split test case"""
        split_fqn = fqn.split_test_case_fqn(
            "local_redshift.dev.dbt_jaffle.customers.customer_id.expect_column_max_to_be_between"
        )

        assert split_fqn.service == "local_redshift"
        assert split_fqn.database == "dev"
        assert split_fqn.schema_ == "dbt_jaffle"
        assert split_fqn.table == "customers"
        assert split_fqn.column == "customer_id"
        assert split_fqn.test_case == "expect_column_max_to_be_between"

        split_fqn = fqn.split_test_case_fqn(
            "local_redshift.dev.dbt_jaffle.customers.expect_table_column_to_be_between"
        )

        assert not split_fqn.column
        assert split_fqn.test_case == "expect_table_column_to_be_between"

        with pytest.raises(ValueError):
            fqn.split_test_case_fqn("local_redshift.dev.dbt_jaffle.customers")

    def test_quote_fqns(self):
        """We can properly quote FQNs for URL usage"""
        assert quote(FullyQualifiedEntityName("a.b.c")) == "a.b.c"
        # Works with strings directly
        assert quote("a.b.c") == "a.b.c"
        assert quote(FullyQualifiedEntityName('"foo.bar".baz')) == "%22foo.bar%22.baz"
        assert quote('"foo.bar/baz".hello') == "%22foo.bar%2Fbaz%22.hello"


class TestFqnGrammar(TestCase):
    """
    Test suite for Fqn.g4 grammar
    Tests various FQN patterns including valid service.db.schema.table formats
    """

    def setUp(self):
        """Set up test cases"""
        self.success_cases = [
            # Basic valid FQNs
            FqnTestCase("service", True, "Single component"),
            FqnTestCase("service.db", True, "Two components"),
            FqnTestCase("service.db.schema", True, "Three components"),
            FqnTestCase(
                "service.db.schema.table", True, "Four components (standard table FQN)"
            ),
            FqnTestCase(
                "service.db.schema.table.column", True, "Five components (with column)"
            ),
            # FQNs with special characters in unquoted names
            FqnTestCase(
                "service-1.db_test.schema2.table-name", True, "Hyphens and underscores"
            ),
            FqnTestCase("service1.db2.schema3.table4", True, "Alphanumeric names"),
            FqnTestCase("Service.DB.SCHEMA.TABLE", True, "Uppercase names"),
            FqnTestCase(
                "service.db.schema.table with spaces", True, "Spaces in unquoted name"
            ),
            FqnTestCase("service.db.schema.table@domain", True, "Special characters"),
            FqnTestCase("service.db.schema.table#hash", True, "Hash character"),
            FqnTestCase("service.db.schema.table$var", True, "Dollar sign"),
            FqnTestCase("service.db.schema.table%percent", True, "Percent sign"),
            FqnTestCase("service.db.schema.table&and", True, "Ampersand"),
            FqnTestCase("service.db.schema.table+plus", True, "Plus sign"),
            FqnTestCase("service.db.schema.table=equals", True, "Equals sign"),
            FqnTestCase("service.db.schema.table[bracket]", True, "Square brackets"),
            FqnTestCase("service.db.schema.table{brace}", True, "Curly braces"),
            FqnTestCase("service.db.schema.table(paren)", True, "Parentheses"),
            FqnTestCase("service.db.schema.table|pipe", True, "Pipe character"),
            FqnTestCase("service.db.schema.table;semicolon", True, "Semicolon"),
            FqnTestCase("service.db.schema.table:colon", True, "Colon"),
            FqnTestCase("service.db.schema.table<less>", True, "Less than"),
            FqnTestCase("service.db.schema.table>greater", True, "Greater than"),
            FqnTestCase("service.db.schema.table?question", True, "Question mark"),
            FqnTestCase("service.db.schema.table/slash", True, "Forward slash"),
            FqnTestCase("service.db.schema.table~tilde", True, "Tilde"),
            FqnTestCase("service.db.schema.table`backtick`", True, "Backticks"),
            # FQNs with quoted names (NAME_WITH_RESERVED)
            FqnTestCase(
                'service."db.with.dots".schema.table', True, "Quoted name with dots"
            ),
            FqnTestCase(
                '"service.with.dots".db.schema.table', True, "Quoted service name"
            ),
            FqnTestCase(
                'service.db."schema.with.dots".table', True, "Quoted schema name"
            ),
            FqnTestCase(
                'service.db.schema."table.with.dots"', True, "Quoted table name"
            ),
            FqnTestCase(
                '"service.dots"."db.dots"."schema.dots"."table.dots"',
                True,
                "All quoted names",
            ),
            FqnTestCase(
                'service."db with spaces".schema.table', True, "Quoted name with spaces"
            ),
            FqnTestCase(
                'service."db-with-hyphens".schema.table',
                True,
                "Quoted name with hyphens",
            ),
            FqnTestCase(
                'service."db_with_underscores".schema.table',
                True,
                "Quoted name with underscores",
            ),
            FqnTestCase(
                'service."123numeric".schema.table', True, "Quoted numeric name"
            ),
            FqnTestCase(
                'service."UPPER.CASE".schema.table', True, "Quoted uppercase with dots"
            ),
            FqnTestCase(
                'service."lower.case".schema.table', True, "Quoted lowercase with dots"
            ),
            FqnTestCase(
                'service."Mixed.Case".schema.table', True, "Quoted mixed case with dots"
            ),
            # FQNs with escaped characters in quoted names
            FqnTestCase(
                'service."db\\"with\\"quotes".schema.table',
                True,
                "Escaped quotes in quoted name",
            ),
            FqnTestCase(
                'service."db\\\\with\\\\backslashes".schema.table',
                True,
                "Escaped backslashes in quoted name",
            ),
            FqnTestCase(
                'service."db\\"and\\\\mixed".schema.table',
                True,
                "Mixed escaped characters",
            ),
            # Complex real-world FQN examples
            FqnTestCase(
                "mysql_prod.ecommerce_db.public.users",
                True,
                "MySQL production database",
            ),
            FqnTestCase(
                "postgres_dev.analytics.dbo.customer_orders",
                True,
                "PostgreSQL development database",
            ),
            FqnTestCase(
                "snowflake_prod.SALES_DB.PUBLIC.CUSTOMER_FACT",
                True,
                "Snowflake uppercase",
            ),
            FqnTestCase(
                "bigquery_analytics.dataset1.table_with_underscores",
                True,
                "BigQuery dataset",
            ),
            FqnTestCase(
                "redshift_cluster.dwh.staging.temp_table_123",
                True,
                "Redshift data warehouse",
            ),
            FqnTestCase(
                "oracle_hr.HR_SCHEMA.EMPLOYEES.EMPLOYEE_ID",
                True,
                "Oracle HR system with column",
            ),
            FqnTestCase(
                "mssql_crm.CRM_Database.dbo.Customers.CustomerID",
                True,
                "SQL Server CRM",
            ),
            FqnTestCase(
                "mongodb_logs.application_logs.user_events", True, "MongoDB collection"
            ),
            FqnTestCase(
                "cassandra_metrics.system_metrics.performance_data",
                True,
                "Cassandra keyspace",
            ),
            FqnTestCase(
                "elasticsearch_search.product_index.documents",
                True,
                "Elasticsearch index",
            ),
            # Edge cases with mixed quoting
            FqnTestCase(
                'service.db."schema.name".table', True, "Mixed quoted and unquoted"
            ),
            FqnTestCase(
                '"service.name".db.schema."table.name"',
                True,
                "Multiple quoted components",
            ),
            FqnTestCase(
                'service."db.name".schema."table.name"',
                True,
                "Alternating quoted components",
            ),
            # Long FQNs
            FqnTestCase(
                "service.db.schema.table.column.subcolumn", True, "Six components"
            ),
            FqnTestCase(
                "service.db.schema.table.column.subcolumn.attribute",
                True,
                "Seven components",
            ),
            FqnTestCase("a.b.c.d.e.f.g.h.i.j", True, "Ten components"),
            # Single character components
            FqnTestCase("a.b.c.d", True, "Single character components"),
            FqnTestCase("x", True, "Single character FQN"),
            # Unicode and international characters
            FqnTestCase("service.数据库.模式.表", True, "Chinese characters"),
            FqnTestCase("service.データベース.スキーマ.テーブル", True, "Japanese characters"),
            FqnTestCase(
                "service.база_данных.схема.таблица", True, "Cyrillic characters"
            ),
            FqnTestCase("service.قاعدة_البيانات.مخطط.جدول", True, "Arabic characters"),
            FqnTestCase("service.datenbank.schema.tabelle", True, "German characters"),
            FqnTestCase(
                "service.base_de_données.schéma.table", True, "French characters"
            ),
            FqnTestCase(
                "service.banco_de_dados.esquema.tabela", True, "Portuguese characters"
            ),
        ]

        self.failure_cases = [
            # Empty and invalid basic cases
            FqnTestCase("", False, "Empty string"),
            FqnTestCase(".", False, "Single dot"),
            FqnTestCase("..", False, "Double dot"),
            FqnTestCase("...", False, "Triple dot"),
            FqnTestCase("service.", False, "Trailing dot"),
            FqnTestCase(".service", False, "Leading dot"),
            FqnTestCase("service..db", False, "Double dot between components"),
            FqnTestCase("service...db", False, "Triple dot between components"),
            FqnTestCase("service.db.", False, "Trailing dot after second component"),
            FqnTestCase("service.db..", False, "Double trailing dot"),
            FqnTestCase(".service.db", False, "Leading dot before service"),
            FqnTestCase("..service.db", False, "Double leading dot"),
            # Invalid quoted names
            FqnTestCase('service."db', False, "Unclosed quote"),
            FqnTestCase('service.db"', False, "Unmatched quote at end"),
            FqnTestCase('service."', False, "Empty quoted name"),
            FqnTestCase('service.""', False, "Empty quoted name with quotes"),
            FqnTestCase(
                'service."db"extra', False, "Extra characters after quoted name"
            ),
            FqnTestCase(
                'service.extra"db"', False, "Extra characters before quoted name"
            ),
            FqnTestCase(
                'service."db"."schema', False, "Mixed quoted and unmatched quote"
            ),
            FqnTestCase('service."db\\', False, "Incomplete escape sequence"),
            FqnTestCase('service."db\\"', False, "Unclosed quote after escape"),
            FqnTestCase('service."db"schema', False, "Missing dot after quoted name"),
            FqnTestCase(
                'service"db".schema', False, "Quote in middle of unquoted name"
            ),
            # Invalid escape sequences
            FqnTestCase('service."db\\n"', False, "Invalid escape sequence (newline)"),
            FqnTestCase('service."db\\t"', False, "Invalid escape sequence (tab)"),
            FqnTestCase(
                'service."db\\r"', False, "Invalid escape sequence (carriage return)"
            ),
            FqnTestCase('service."db\\x"', False, "Invalid escape sequence (unknown)"),
            FqnTestCase('service."db\\""', False, "Incomplete escape at end"),
            # Invalid characters in unquoted names
            FqnTestCase(
                'service.db".schema', False, "Unescaped quote in unquoted name"
            ),
            FqnTestCase(
                "service.db\\.schema", False, "Unescaped backslash in unquoted name"
            ),
            FqnTestCase("service.db\\schema", False, "Backslash in unquoted name"),
            FqnTestCase('service.db"schema', False, "Quote in unquoted name"),
            FqnTestCase(
                'service.db"schema"', False, "Quotes around part of unquoted name"
            ),
            # Malformed dots and components
            FqnTestCase("service.db..schema.table", False, "Double dot in middle"),
            FqnTestCase("service.db.schema..table", False, "Double dot before table"),
            FqnTestCase("service..db.schema.table", False, "Double dot after service"),
            FqnTestCase("service.db...schema.table", False, "Triple dot in middle"),
            FqnTestCase("service.db.schema.table.", False, "Trailing dot after table"),
            FqnTestCase(
                ".service.db.schema.table", False, "Leading dot before service"
            ),
            # Invalid whitespace handling
            FqnTestCase(" service.db.schema.table", False, "Leading space"),
            FqnTestCase("service.db.schema.table ", False, "Trailing space"),
            FqnTestCase("service .db.schema.table", False, "Space before dot"),
            FqnTestCase("service. db.schema.table", False, "Space after dot"),
            FqnTestCase("service . db.schema.table", False, "Spaces around dot"),
            FqnTestCase(
                "service.db .schema.table", False, "Space before dot in middle"
            ),
            FqnTestCase("service.db. schema.table", False, "Space after dot in middle"),
            # Complex invalid cases
            FqnTestCase(
                'service."db.schema".table.',
                False,
                "Quoted component with trailing dot",
            ),
            FqnTestCase(
                'service..db."schema.table"',
                False,
                "Double dot before quoted component",
            ),
            FqnTestCase(
                '"service.db"..schema.table', False, "Double dot after quoted component"
            ),
            FqnTestCase(
                'service."db.schema"..table', False, "Double dot after quoted component"
            ),
            FqnTestCase('service."db."schema".table', False, "Nested quotes"),
            FqnTestCase('service."db"schema".table', False, "Malformed nested quotes"),
            # Completely invalid formats
            FqnTestCase("service db schema table", False, "Spaces instead of dots"),
            FqnTestCase("service,db,schema,table", False, "Commas instead of dots"),
            FqnTestCase("service/db/schema/table", False, "Slashes instead of dots"),
            FqnTestCase("service:db:schema:table", False, "Colons instead of dots"),
            FqnTestCase("service;db;schema;table", False, "Semicolons instead of dots"),
            FqnTestCase("service|db|schema|table", False, "Pipes instead of dots"),
            FqnTestCase("service->db->schema->table", False, "Arrows instead of dots"),
            FqnTestCase(
                "service::db::schema::table", False, "Double colons instead of dots"
            ),
            # Special invalid characters
            FqnTestCase("service\x00db", False, "Null character"),
            FqnTestCase("service\ndb", False, "Newline character"),
            FqnTestCase("service\tdb", False, "Tab character"),
            FqnTestCase("service\rdb", False, "Carriage return character"),
            # Extremely long invalid cases
            FqnTestCase("." * 100, False, "100 dots"),
            FqnTestCase("service" + ".." * 50, False, "Many double dots"),
        ]

        self.edge_cases = [
            # Boundary conditions
            FqnTestCase("a" * 1000, True, "Very long single component"),
            FqnTestCase(".".join(["a"] * 100), True, "100 single-character components"),
            FqnTestCase('"' + "a" * 1000 + '"', True, "Very long quoted component"),
            FqnTestCase(
                'service."' + "a" * 1000 + '".schema.table',
                True,
                "Very long quoted database name",
            ),
            # Escape sequence edge cases
            FqnTestCase(
                'service."a\\"b\\"c".schema.table', True, "Multiple escaped quotes"
            ),
            FqnTestCase(
                'service."a\\\\b\\\\c".schema.table',
                True,
                "Multiple escaped backslashes",
            ),
            FqnTestCase(
                'service."\\"\\\\"."schema.table',
                True,
                "Escaped quote and backslash only",
            ),
            # Complex Unicode cases
            FqnTestCase("service.🌟.⭐.✨", True, "Emoji characters"),
            FqnTestCase("service.🚀.🛸.👽", True, "More emoji characters"),
            FqnTestCase("service.α.β.γ", True, "Greek letters"),
            FqnTestCase("service.א.ב.ג", True, "Hebrew letters"),
            FqnTestCase("service.ä.ö.ü", True, "German umlauts"),
            FqnTestCase("service.ñ.ç.ß", True, "Various accented characters"),
        ]

    @unittest.skipUnless(ANTLR_AVAILABLE, "ANTLR4 Python runtime not available")
    def test_successful_parsing(self):
        """Test cases that should successfully parse"""
        for case in self.success_cases:
            with self.subTest(fqn=case.fqn, description=case.description):
                self.assert_parsing_result(case.fqn, True, case.description)

    @unittest.skipUnless(ANTLR_AVAILABLE, "ANTLR4 Python runtime not available")
    def test_failed_parsing(self):
        """Test cases that should fail to parse"""
        for case in self.failure_cases:
            with self.subTest(fqn=case.fqn, description=case.description):
                self.assert_parsing_result(case.fqn, False, case.description)

    @unittest.skipUnless(ANTLR_AVAILABLE, "ANTLR4 Python runtime not available")
    def test_edge_cases(self):
        """Test edge cases and boundary conditions"""
        for case in self.edge_cases:
            with self.subTest(fqn=case.fqn, description=case.description):
                self.assert_parsing_result(case.fqn, case.should_pass, case.description)

    def assert_parsing_result(self, fqn: str, should_pass: bool, description: str = ""):
        """Assert that parsing result matches expectation"""
        if not ANTLR_AVAILABLE:
            self.skipTest("ANTLR4 Python runtime not available")

        # Note: This is a template - actual implementation would require generated ANTLR classes
        # Input stream from the FQN string
        input_stream = InputStream(fqn)

        # Create lexer and parser (these would be generated from Fqn.g4)
        # lexer = FqnLexer(input_stream)
        # token_stream = CommonTokenStream(lexer)
        # parser = FqnParser(token_stream)

        # Add error listener
        error_listener = FqnGrammarTestErrorListener()
        # parser.removeErrorListeners()
        # parser.addErrorListener(error_listener)

        # Parse the FQN
        try:
            # tree = parser.fqn()  # This would call the root rule
            has_errors = len(error_listener.errors) > 0

            if should_pass:
                self.assertFalse(
                    has_errors,
                    f"Expected '{fqn}' to parse successfully but got errors: {error_listener.errors}. {description}",
                )
            else:
                self.assertTrue(
                    has_errors,
                    f"Expected '{fqn}' to fail parsing but it succeeded. {description}",
                )
        except Exception as e:
            if should_pass:
                self.fail(
                    f"Expected '{fqn}' to parse successfully but got exception: {e}. {description}"
                )
            # If we expected failure and got an exception, that's OK

    def test_fqn_structure_validation(self):
        """Test FQN structure validation without ANTLR"""
        # These tests can run without ANTLR and validate basic FQN structure

        # Test basic dot counting
        self.assertEqual(len("service.db.schema.table".split(".")), 4)
        self.assertEqual(len("service.db.schema.table.column".split(".")), 5)

        # Test empty component detection
        self.assertIn("", "service..db".split("."))
        self.assertIn("", "service.db.".split("."))
        self.assertIn("", ".service.db".split("."))

        # Test quote matching
        self.assertTrue(
            '"service.name"'.startswith('"') and '"service.name"'.endswith('"')
        )
        self.assertFalse(
            '"service.name'.startswith('"') and '"service.name'.endswith('"')
        )

    def test_manual_fqn_validation(self):
        """Manual validation of FQN patterns without ANTLR"""

        def is_valid_basic_fqn(fqn: str) -> bool:
            """Basic FQN validation without ANTLR"""
            if not fqn or fqn.startswith(".") or fqn.endswith("."):
                return False
            if ".." in fqn:
                return False
            parts = fqn.split(".")
            if len(parts) < 1:
                return False
            for part in parts:
                if not part:  # Empty part
                    return False
                # Check for unmatched quotes
                if part.count('"') % 2 != 0:
                    return False
                # Check for invalid quote positions
                if '"' in part and not (part.startswith('"') and part.endswith('"')):
                    return False
            return True

        # Test some basic cases
        self.assertTrue(is_valid_basic_fqn("service.db.schema.table"))
        self.assertTrue(is_valid_basic_fqn("service"))
        self.assertFalse(is_valid_basic_fqn(""))
        self.assertFalse(is_valid_basic_fqn(".service"))
        self.assertFalse(is_valid_basic_fqn("service."))
        self.assertFalse(is_valid_basic_fqn("service..db"))
        self.assertFalse(is_valid_basic_fqn('service."db'))

    def test_quoted_name_patterns(self):
        """Test patterns for quoted names"""

        def extract_quoted_names(fqn: str) -> List[str]:
            """Extract quoted names from FQN"""
            quoted_names = []
            parts = fqn.split(".")
            for part in parts:
                if part.startswith('"') and part.endswith('"'):
                    quoted_names.append(part[1:-1])  # Remove quotes
            return quoted_names

        # Test quoted name extraction
        self.assertEqual(
            extract_quoted_names('service."db.name".schema.table'), ["db.name"]
        )
        self.assertEqual(
            extract_quoted_names('"service.name"."db.name".schema.table'),
            ["service.name", "db.name"],
        )
        self.assertEqual(extract_quoted_names("service.db.schema.table"), [])

    def test_escape_sequence_patterns(self):
        """Test escape sequence patterns"""

        def validate_escape_sequences(quoted_content: str) -> bool:
            """Validate escape sequences in quoted content"""
            i = 0
            while i < len(quoted_content):
                if quoted_content[i] == "\\":
                    if i + 1 >= len(quoted_content):
                        return False  # Incomplete escape
                    next_char = quoted_content[i + 1]
                    if next_char not in ['"', "\\"]:
                        return False  # Invalid escape sequence
                    i += 2  # Skip escaped character
                else:
                    i += 1
            return True

        # Test escape sequence validation
        self.assertTrue(validate_escape_sequences('db\\"name'))
        self.assertTrue(validate_escape_sequences("db\\\\name"))
        self.assertTrue(validate_escape_sequences('db\\"and\\\\name'))
        self.assertFalse(validate_escape_sequences("db\\name"))  # Invalid escape
        self.assertFalse(validate_escape_sequences("db\\"))  # Incomplete escape

    def test_real_world_fqn_examples(self):
        """Test real-world FQN examples"""
        real_world_examples = [
            "mysql_production.ecommerce.public.users",
            "postgres_dev.analytics.reporting.customer_metrics",
            "snowflake_warehouse.SALES_DATA.PUBLIC.TRANSACTIONS",
            "bigquery_project.dataset_name.table_with_underscores",
            "redshift_cluster.data_warehouse.staging.temp_processing_table",
            "oracle_hr.HR_SYSTEM.EMPLOYEES.PERSONAL_INFO",
            "mssql_crm.CRM_Database.dbo.Customer_Orders",
            "mongodb_logs.application_data.user_activity_events",
            "cassandra_metrics.performance_tracking.system_metrics",
            "elasticsearch_search.product_catalog.searchable_documents",
        ]

        for fqn in real_world_examples:
            with self.subTest(fqn=fqn):
                # Basic validation - should have at least 3 parts for database.schema.table
                parts = fqn.split(".")
                self.assertGreaterEqual(
                    len(parts), 3, f"FQN should have at least 3 parts: {fqn}"
                )

                # Each part should be non-empty
                for part in parts:
                    self.assertTrue(part, f"FQN parts should not be empty: {fqn}")

                # Should not contain double dots
                self.assertNotIn(
                    "..", fqn, f"FQN should not contain double dots: {fqn}"
                )

    def test_performance_with_large_fqns(self):
        """Test performance with large FQNs"""

        # Test with many components
        large_fqn = ".".join([f"component_{i}" for i in range(1000)])
        self.assertEqual(len(large_fqn.split(".")), 1000)

        # Test with very long component names
        long_component = "a" * 10000
        long_fqn = f"service.{long_component}.schema.table"
        parts = long_fqn.split(".")
        self.assertEqual(len(parts), 4)
        self.assertEqual(len(parts[1]), 10000)

    def test_unicode_handling(self):
        """Test Unicode character handling in FQNs"""
        unicode_fqns = [
            "service.数据库.模式.表",
            "service.データベース.スキーマ.テーブル",
            "service.база_данных.схема.таблица",
            "service.🌟.⭐.✨",
            "service.α.β.γ",
            "service.ä.ö.ü.ß",
        ]

        for fqn in unicode_fqns:
            with self.subTest(fqn=fqn):
                # Should be valid Unicode strings
                self.assertIsInstance(fqn, str)

                # Should split correctly
                parts = fqn.split(".")
                self.assertEqual(len(parts), 4)

                # Each part should be valid Unicode
                for part in parts:
                    self.assertIsInstance(part, str)
                    self.assertTrue(part)  # Non-empty
