package com.altamiracorp.bigtable.model.accumulo;

import com.altamiracorp.bigtable.model.Column;
import com.altamiracorp.bigtable.model.*;
import com.altamiracorp.bigtable.model.Value;
import com.altamiracorp.bigtable.model.exceptions.MutationsWriteException;
import com.altamiracorp.bigtable.model.exceptions.TableDoesNotExistException;
import com.altamiracorp.bigtable.model.user.accumulo.AccumuloUserContext;
import com.beust.jcommander.internal.Lists;
import com.beust.jcommander.internal.Maps;
import org.apache.accumulo.core.client.*;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.mock.MockConnector;
import org.apache.accumulo.core.client.mock.MockInstance;
import org.apache.accumulo.core.client.security.SecurityErrorCode;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.data.*;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.hadoop.security.UserGroupInformation;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.reflect.Whitebox;

import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;

@RunWith(PowerMockRunner.class)
@PrepareForTest(AccumuloHelper.class)
public class AccumuloSessionTest {
    private static final String TEST_TABLE_NAME = "testTable";
    private AccumuloSession accumuloSession;
    private MockInstance mockInstance;
    private MockConnector connector;
    private Authorizations authorizations;
    private long maxMemory = 1000000L;
    private long maxLatency = 1000L;
    private int maxWriteThreads = 10;
    private AccumuloUserContext queryUser;
    private AccumuloUserContext adminUser;

    private static final Row TEST_ROW = new Row<RowKey>(TEST_TABLE_NAME, new RowKey("foobar"));

    @Before
    public void before() throws AccumuloSecurityException, AccumuloException {
        MockitoAnnotations.initMocks(this);

        String[] userGroups = new String[0];
        UserGroupInformation.setLoginUser(UserGroupInformation.createUserForTesting("test", userGroups));
        mockInstance = new MockInstance();
        connector = (MockConnector) mockInstance.getConnector("testUser", new PasswordToken("testPassword".getBytes()));

        authorizations = new Authorizations("ALL");
        queryUser = new AccumuloUserContext(authorizations);
        adminUser = new AccumuloUserContext(new Authorizations("A", "B"));

        accumuloSession = new AccumuloSession(connector, true);
        accumuloSession.initializeTable(TEST_TABLE_NAME, queryUser);
    }

    @Test(expected = MutationsWriteException.class)
    public void testSaveThrowsMutationsWriteException() throws MutationsRejectedException {
        setupMutationsRejectedException();

        accumuloSession.save(TEST_ROW);
    }

    @Test(expected = NullPointerException.class)
    public void testSaveNullRow() {
        accumuloSession.save(null);
    }

    @Test(expected = TableDoesNotExistException.class)
    public void testSaveThrowsTableDoesNotExistException() throws Exception {
        final Connector mockConnector = mock(Connector.class);
        final AccumuloSession session = new AccumuloSession(mockConnector, true);

        when(mockConnector.createBatchWriter(anyString(), any(BatchWriterConfig.class))).thenThrow(new TableNotFoundException("Id", TEST_TABLE_NAME, "Not found"));

        session.save(TEST_ROW);
    }

    @Test(expected = MutationsWriteException.class)
    public void testFlushThrowsMutationsWriteException() throws Exception {
        final Connector mockConnector = mock(Connector.class);
        final BatchWriter mockWriter = mock(BatchWriter.class);

        final AccumuloSession session = new AccumuloSession(mockConnector, true);
        final MutationsRejectedException mutationException = createMutationsException();

        doThrow(mutationException).when(mockWriter).flush();
        when(mockConnector.createBatchWriter(anyString(), any(BatchWriterConfig.class))).thenReturn(mockWriter);

        final Map<String, BatchWriter> writers = Maps.newHashMap();
        writers.put("foo", mockWriter);

        Whitebox.setInternalState(session, "batchWriters", writers);
        session.flush();
    }

    @Test(expected = MutationsWriteException.class)
    public void testSaveManyThrowsMutationsWriteException() throws MutationsRejectedException {
        setupMutationsRejectedException();

        accumuloSession.saveMany(TEST_TABLE_NAME, Lists.newArrayList(TEST_ROW));
    }

    private void setupMutationsRejectedException() throws MutationsRejectedException {
        PowerMockito.mockStatic(AccumuloHelper.class);

        final MutationsRejectedException mutationException = createMutationsException();

        // Setup the static helper method to throw an exception
        when(AccumuloHelper.addRowToWriter(any(BatchWriter.class), any(Row.class))).thenThrow(mutationException);
    }

    private MutationsRejectedException createMutationsException() {
        final List<ConstraintViolationSummary> cvsList = Lists.newArrayList();
        final Map<KeyExtent, Set<SecurityErrorCode>> authFailuresMap = Maps.newHashMap();
        final List<String> serverErrorList = Lists.newArrayList();

        return new MutationsRejectedException(cvsList, (HashMap<KeyExtent, Set<SecurityErrorCode>>) authFailuresMap, serverErrorList, -1, new Throwable());
    }

    @Test
    public void testSave() throws TableNotFoundException {
        Row row = new Row<RowKey>(TEST_TABLE_NAME, new RowKey("testRowKey1"));

        ColumnFamily columnFamily1 = new ColumnFamily("testColumnFamily1");
        columnFamily1.set("1testColumn1", "1testColumn1Value");
        columnFamily1.set("1testColumn2", "1testColumn2Value");
        columnFamily1.set("1testColumn3", 111L);
        columnFamily1.set("1testColumn4", "test".getBytes());
        row.addColumnFamily(columnFamily1);

        ColumnFamily columnFamily2 = new ColumnFamily("testColumnFamily2");
        columnFamily2.set("2testColumn1", "2testColumn1Value");
        columnFamily2.set("2testColumn2", "2testColumn2Value");
        columnFamily2.set("2testColumn3", 222L);
        row.addColumnFamily(columnFamily2);

        accumuloSession.save(row);

        Scanner scanner = connector.createScanner(TEST_TABLE_NAME, authorizations);
        scanner.setRange(new Range("testRowKey1"));
        RowIterator rowIterator = new RowIterator(scanner);
        int rowCount = 0;
        while (rowIterator.hasNext()) {
            if (rowCount != 0) {
                fail("Too many rows");
            }

            Iterator<Map.Entry<Key, org.apache.accumulo.core.data.Value>> accumuloRow = rowIterator.next();
            int colunnCount = 0;
            while (accumuloRow.hasNext()) {
                Map.Entry<Key, org.apache.accumulo.core.data.Value> accumuloColumn = accumuloRow.next();
                String columnFamilyString = accumuloColumn.getKey().getColumnFamily().toString();
                String columnNameString = accumuloColumn.getKey().getColumnQualifier().toString();

                Assert.assertEquals("testRowKey1", accumuloColumn.getKey().getRow().toString());
                if ("testColumnFamily1".equals(columnFamilyString)) {
                    if ("1testColumn1".equals(columnNameString)) {
                        Assert.assertEquals("1testColumn1Value", accumuloColumn.getValue().toString());
                    } else if ("1testColumn2".equals(columnNameString)) {
                        Assert.assertEquals("1testColumn2Value", accumuloColumn.getValue().toString());
                    } else if ("1testColumn3".equals(columnNameString)) {
                        Assert.assertEquals(111L, new Value(accumuloColumn.getValue().get()).toLong().longValue());
                    } else if ("1testColumn4".equals(columnNameString)) {
                        Assert.assertEquals("test", accumuloColumn.getValue().toString());
                    } else {
                        fail("invalid column name: " + columnFamilyString + " - " + columnNameString);
                    }
                } else if ("testColumnFamily2".equals(columnFamilyString)) {
                    if ("2testColumn1".equals(columnNameString)) {
                        Assert.assertEquals("2testColumn1Value", accumuloColumn.getValue().toString());
                    } else if ("2testColumn2".equals(columnNameString)) {
                        Assert.assertEquals("2testColumn2Value", accumuloColumn.getValue().toString());
                    } else if ("2testColumn3".equals(columnNameString)) {
                        Assert.assertEquals(222L, new Value(accumuloColumn.getValue().get()).toLong().longValue());
                    } else {
                        fail("invalid column name: " + columnFamilyString + " - " + columnNameString);
                    }
                } else {
                    fail("invalid column family name: " + columnFamilyString);
                }
                colunnCount++;
            }
            Assert.assertEquals(7, colunnCount);

            rowCount++;
        }
    }

    @Test
    public void testFindByRowKey() throws TableNotFoundException, MutationsRejectedException {
        BatchWriter writer = connector.createBatchWriter(TEST_TABLE_NAME, maxMemory, maxLatency, maxWriteThreads);
        Mutation mutation = new Mutation("testRowKey");
        mutation.put("testColumnFamily1", "1testColumn1", "1testValue1");
        mutation.put("testColumnFamily1", "1testColumn2", "1testValue2");
        mutation.put("testColumnFamily1", "1testColumn3", new org.apache.accumulo.core.data.Value(new Value(111L).toBytes()));
        mutation.put("testColumnFamily2", "2testColumn1", "2testValue1");
        writer.addMutation(mutation);
        writer.close();

        Row row = accumuloSession.findByRowKey(TEST_TABLE_NAME, "testRowKey", queryUser);
        assertEquals(TEST_TABLE_NAME, row.getTableName());
        assertEquals("testRowKey", row.getRowKey().toString());
        assertEquals(2, row.getColumnFamilies().size());

        ColumnFamily testColumnFamily1 = row.get("testColumnFamily1");
        assertEquals(3, testColumnFamily1.getColumns().size());
        assertEquals("1testValue1", testColumnFamily1.get("1testColumn1").toString());
        assertEquals("1testValue2", testColumnFamily1.get("1testColumn2").toString());
        assertEquals(111L, testColumnFamily1.get("1testColumn3").toLong().longValue());

        ColumnFamily testColumnFamily2 = row.get("testColumnFamily2");
        assertEquals(1, testColumnFamily2.getColumns().size());
        assertEquals("2testValue1", testColumnFamily2.get("2testColumn1").toString());
    }

    @Test
    public void testFindByRowStartsWith() throws TableNotFoundException, MutationsRejectedException {
        BatchWriter writer = connector.createBatchWriter(TEST_TABLE_NAME, maxMemory, maxLatency, maxWriteThreads);
        Mutation mutation = new Mutation("testRowKey1");
        mutation.put("testColumnFamily1", "1testColumn1", "1testValue1");
        writer.addMutation(mutation);

        mutation = new Mutation("testRow2");
        mutation.put("testColumnFamily1", "1testColumn1", "2testValue2");
        writer.addMutation(mutation);

        mutation = new Mutation("testRowKeyzSample");
        mutation.put("testColumnFamily1", "1testColumn1", "3testValue3");
        writer.addMutation(mutation);
        writer.close();

        List<Row> row = toList(accumuloSession.findByRowStartsWith(TEST_TABLE_NAME, "testRowKey", queryUser));
        assertEquals(2, row.size());


        // Ensure that only two of the three row keys are returned
        assertEquals("testRowKey1", row.get(0).getRowKey().toString());
        assertEquals("testRowKeyzSample", row.get(1).getRowKey().toString());
    }

    @Test
    public void testFindAll() throws TableNotFoundException, MutationsRejectedException {
        BatchWriter writer = connector.createBatchWriter(TEST_TABLE_NAME, maxMemory, maxLatency, maxWriteThreads);

        List<Row> rows = toList(accumuloSession.findAll(TEST_TABLE_NAME, queryUser));
        assertEquals(0, rows.size());

        Mutation mutation = new Mutation("testRowKey1");
        mutation.put("testColumnFamily1", "testColumn1", "testValue1");
        writer.addMutation(mutation);

        mutation = new Mutation("testRowKey2");
        mutation.put("testColumnFamily2", "testColumn2", "testValue2");
        writer.addMutation(mutation);

        rows = toList(accumuloSession.findAll(TEST_TABLE_NAME, queryUser));
        assertEquals(2, rows.size());
    }

    @Test
    public void testFindByRowKeyRange() throws TableNotFoundException, MutationsRejectedException {
        BatchWriter writer = connector.createBatchWriter(TEST_TABLE_NAME, maxMemory, maxLatency, maxWriteThreads);

        Mutation mutation = new Mutation("testRowKey1");
        mutation.put("testColumnFamily1", "testColumn1", "testValue1");
        writer.addMutation(mutation);

        mutation = new Mutation("testRowKey2");
        mutation.put("testColumnFamily2", "testColumn2", "testValue2");
        writer.addMutation(mutation);

        writer.close();

        List<Row> rows = toList(accumuloSession.findByRowKeyRange(TEST_TABLE_NAME, "testRowKey", "testRowKeyZ", queryUser));
        assertEquals(2, rows.size());

        Row row1 = rows.get(0);
        assertEquals("testRowKey1", row1.getRowKey().toString());
        assertEquals("testValue1", row1.get("testColumnFamily1").get("testColumn1").toString());

        Row row2 = rows.get(1);
        assertEquals("testRowKey2", row2.getRowKey().toString());
        assertEquals("testValue2", row2.get("testColumnFamily2").get("testColumn2").toString());
    }

    @Test
    public void testFindByRowKeyRegex() throws TableNotFoundException, MutationsRejectedException {
        BatchWriter writer = connector.createBatchWriter(TEST_TABLE_NAME, maxMemory, maxLatency, maxWriteThreads);

        Mutation mutation = new Mutation("testRowKey1");
        mutation.put("testColumnFamily1", "testColumn1", "testValue1");
        writer.addMutation(mutation);

        mutation = new Mutation("testRowKey2");
        mutation.put("testColumnFamily2", "testColumn2", "testValue2");
        writer.addMutation(mutation);

        writer.close();

        List<Row> rows = toList(accumuloSession.findByRowKeyRegex(TEST_TABLE_NAME, ".*1", queryUser));
        assertEquals(1, rows.size());

        Row row1 = rows.get(0);
        assertEquals("testRowKey1", row1.getRowKey().toString());
        assertEquals("testValue1", row1.get("testColumnFamily1").get("testColumn1").toString());
    }

    private List<Row> toList(Iterable<Row> rows) {
        List<Row> result = new ArrayList<Row>();
        for (Row row : rows) {
            result.add(row);
        }
        return result;
    }

    @Test
    public void testDeleteRow() {
        Row row = new Row<RowKey>(TEST_TABLE_NAME, new RowKey("testRowKey1"));
        ColumnFamily columnFamily1 = new ColumnFamily("testColumnFamily1");
        columnFamily1.set("1testColumn1", "1testColumn1Value");
        row.addColumnFamily(columnFamily1);

        accumuloSession.save(row);

        row = accumuloSession.findByRowKey(TEST_TABLE_NAME, "testRowKey1", queryUser);
        assertNotNull("row should exist", row);

        accumuloSession.deleteRow(TEST_TABLE_NAME, new RowKey("testRowKey1"));

        row = accumuloSession.findByRowKey(TEST_TABLE_NAME, "testRowKey1", queryUser);
        assertNull("row should be deleted", row);
    }

    @Test
    public void testColumnVisibility() {
        AccumuloUserContext queryUserWithAuth = new AccumuloUserContext(new Authorizations("B"));
        Row row = new Row<RowKey>(TEST_TABLE_NAME, new RowKey("testRowKey1"));
        ColumnFamily columnFamily = new ColumnFamily("testColumnFamily1");
        columnFamily.set("testColumn1", "testValue1");
        columnFamily.addColumn(new Column("testColumn2", new Value("testValue2"), "A|B"));
        columnFamily.addColumn(new Column("testColumn3", new Value("testValue3"), "A"));
        row.addColumnFamily(columnFamily);

        accumuloSession.save(row);

        Row adminQueryRow = accumuloSession.findByRowKey(TEST_TABLE_NAME, "testRowKey1", adminUser);
        ColumnFamily adminQueryColumnFamily = adminQueryRow.get("testColumnFamily1");
        assertEquals(3, adminQueryColumnFamily.getColumns().size());

        Row staffQueryRow = accumuloSession.findByRowKey(TEST_TABLE_NAME, "testRowKey1", queryUserWithAuth);
        ColumnFamily staffQueryColumnFamily = staffQueryRow.get("testColumnFamily1");
        assertEquals(2, staffQueryColumnFamily.getColumns().size());
    }

    @Test
    public void testDeleteColumn() {
        Row row = new Row<RowKey>(TEST_TABLE_NAME, new RowKey("testRowKey1"));
        AccumuloUserContext queryUserWithAuth = new AccumuloUserContext(new Authorizations("B"));
        ColumnFamily columnFamily = new ColumnFamily("testColumnFamily1");
        columnFamily.set("testColumn1", "testValue1");
        columnFamily.set("testColumn2", new Value("testValue2"), "A|B");
        columnFamily.set("testColumn3", new Value("testValue3"), "A");
        row.addColumnFamily(columnFamily);

        accumuloSession.save(row);

        Row staffQueryRow = accumuloSession.findByRowKey(TEST_TABLE_NAME, "testRowKey1", queryUserWithAuth);
        ColumnFamily staffQueryColumnFamily = staffQueryRow.get("testColumnFamily1");
        List<Column> columns = new ArrayList<Column>(staffQueryColumnFamily.getColumns());

        assertEquals(2, staffQueryColumnFamily.getColumns().size());
        assertTrue(columns.get(0).getName().equals("testColumn2"));
        assertTrue(columns.get(1).getName().equals("testColumn1"));

        columns.get(0).setDirty(true);
        accumuloSession.deleteColumn(staffQueryRow, staffQueryRow.getTableName(), "testColumnFamily1", "testColumn2", "A|B");
        staffQueryRow = accumuloSession.findByRowKey(TEST_TABLE_NAME, "testRowKey1", queryUserWithAuth);
        staffQueryColumnFamily = staffQueryRow.get("testColumnFamily1");
        columns = new ArrayList<Column>(staffQueryColumnFamily.getColumns());
        assertEquals(1, columns.size());
        assertTrue(columns.get(0).getName().equals("testColumn1"));
    }

    @Test
    public void testSetWithColumnVisibility() {
        Row row = new Row<RowKey>(TEST_TABLE_NAME, new RowKey("testRowKey1"));
        AccumuloUserContext queryUserWithAuth = new AccumuloUserContext(new Authorizations("B"));
        ColumnFamily columnFamily = new ColumnFamily("testColumnFamily1");
        columnFamily.set("testColumn1", "testValue1");
        columnFamily.set("testColumn2", new Value("testValue2"), "A|B");
        columnFamily.set("testColumn3", new Value("testValue3"), "A");
        row.addColumnFamily(columnFamily);

        accumuloSession.save(row);

        Row adminQueryRow = accumuloSession.findByRowKey(TEST_TABLE_NAME, "testRowKey1", adminUser);
        ColumnFamily adminQueryColumnFamily = adminQueryRow.get("testColumnFamily1");
        assertEquals(3, adminQueryColumnFamily.getColumns().size());

        Row staffQueryRow = accumuloSession.findByRowKey(TEST_TABLE_NAME, "testRowKey1", queryUserWithAuth);
        ColumnFamily staffQueryColumnFamily = staffQueryRow.get("testColumnFamily1");
        assertEquals(2, staffQueryColumnFamily.getColumns().size());
    }

    @Test
    public void testAlterColumnsVisibility() {
        Row row = new Row<RowKey>(TEST_TABLE_NAME, new RowKey("testRowKey1"));
        AccumuloUserContext queryUserWithAuthA = new AccumuloUserContext(new Authorizations("A"));
        AccumuloUserContext queryUserWithAuthB = new AccumuloUserContext(new Authorizations("B"));
        AccumuloUserContext queryUserWithAuthBandC = new AccumuloUserContext(new Authorizations("B", "C"));
        ColumnFamily columnFamily = new ColumnFamily("testColumnFamily1");
        columnFamily.set("testColumn1", new Value("testValue1"), "A");
        columnFamily.set("testColumn2", new Value("testValue2"), "A");
        columnFamily.set("testColumn3", new Value("testValue3"), "C");
        columnFamily.set("testColumn4", new Value("testValue4"), "C");
        row.addColumnFamily(columnFamily);

        accumuloSession.alterColumnsVisibility(row, "A", "B", FlushFlag.FLUSH);
        assertNull(accumuloSession.findByRowKey(row.getTableName(), row.getRowKey().toString(), queryUserWithAuthA));
        Row alteredRow = accumuloSession.findByRowKey(row.getTableName(), row.getRowKey().toString(), queryUserWithAuthBandC);
        assertNotNull(alteredRow);
        List<Column> columns = new ArrayList<Column>(alteredRow.get("testColumnFamily1").getColumns());
        assertEquals(4, columns.size());
        assertTrue(columns.get(0).getName().equals("testColumn2") && columns.get(0).getVisibility().equals("B"));
        assertTrue(columns.get(1).getName().equals("testColumn3") && columns.get(1).getVisibility().equals("C"));
        assertTrue(columns.get(2).getName().equals("testColumn1") && columns.get(2).getVisibility().equals("B"));
        assertTrue(columns.get(3).getName().equals("testColumn4") && columns.get(3).getVisibility().equals("C"));

        accumuloSession.save(row, FlushFlag.FLUSH);
        assertNotNull(accumuloSession.findByRowKey(row.getTableName(), row.getRowKey().toString(), queryUserWithAuthA));
        assertNotNull(accumuloSession.findByRowKey(row.getTableName(), row.getRowKey().toString(), queryUserWithAuthB));
    }
}
