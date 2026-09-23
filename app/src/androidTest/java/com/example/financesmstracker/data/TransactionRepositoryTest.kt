package com.example.financesmstracker.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.financesmstracker.parser.AccountType
import com.example.financesmstracker.parser.PaymentMethod
import com.example.financesmstracker.parser.TransactionType
import com.example.financesmstracker.util.HashUtil
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransactionRepositoryTest {

    private lateinit var dbHelper: FinanceDatabaseHelper
    private lateinit var repository: TransactionRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("finance_tracker.db")
        dbHelper = FinanceDatabaseHelper(context)
        repository = TransactionRepository(dbHelper)
    }

    @After
    fun tearDown() {
        dbHelper.close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("finance_tracker.db")
    }

    @Test
    fun testInsertAndReadTransaction() {
        val hash = HashUtil.sha256("Test SMS 1")
        val tx = Transaction(
            amountPaise = 50000L,
            transactionType = TransactionType.DEBIT,
            paymentMethod = PaymentMethod.UPI,
            accountType = AccountType.BANK_ACCOUNT,
            bank = "HDFC",
            merchantName = "Swiggy",
            payeeId = "swiggy@upi",
            accountLastFour = "1234",
            refNumber = "123456",
            timestamp = 1690000000L,
            smsHash = hash,
            category = "FOOD",
            parserConfidence = 0.95f
        )

        val rowId = repository.insertTransaction(tx)
        assertTrue(rowId > 0)

        val retrieved = repository.getTransactionById(rowId)
        assertNotNull(retrieved)
        assertEquals(50000L, retrieved?.amountPaise)
        assertEquals(TransactionType.DEBIT, retrieved?.transactionType)
        assertEquals("swiggy@upi", retrieved?.payeeId)
        assertEquals("FOOD", retrieved?.category)
    }

    @Test
    fun testDuplicateSmsPrevention() {
        val hash = HashUtil.sha256("Duplicate SMS")
        val tx1 = Transaction(
            amountPaise = 1000L,
            transactionType = TransactionType.DEBIT,
            paymentMethod = PaymentMethod.UPI,
            accountType = AccountType.BANK_ACCOUNT,
            bank = "HDFC",
            merchantName = "Test",
            payeeId = "test@upi",
            accountLastFour = "1234",
            refNumber = "001",
            timestamp = 1690000000L,
            smsHash = hash,
            category = "OTHER",
            parserConfidence = 0.9f
        )

        val id1 = repository.insertTransaction(tx1)
        assertTrue(id1 > 0)

        val id2 = repository.insertTransaction(tx1)
        assertEquals(-1L, id2)

        val all = repository.getAllTransactions()
        assertEquals(1, all.size)
    }

    @Test
    fun testUpdateTransactionCategory() {
        val hash = HashUtil.sha256("Update test")
        val tx = Transaction(
            amountPaise = 2000L,
            transactionType = TransactionType.DEBIT,
            paymentMethod = PaymentMethod.CARD,
            accountType = AccountType.CREDIT_CARD,
            bank = "AXIS",
            merchantName = "Amazon",
            payeeId = null,
            accountLastFour = "5678",
            refNumber = "002",
            timestamp = 1690000000L,
            smsHash = hash,
            category = "OTHER",
            parserConfidence = 0.9f
        )
        val id = repository.insertTransaction(tx)
        
        val rowsUpdated = repository.updateTransactionCategory(id, "SHOPPING")
        assertEquals(1, rowsUpdated)

        val retrieved = repository.getTransactionById(id)
        assertEquals("SHOPPING", retrieved?.category)
    }

    @Test
    fun testDeleteTransaction() {
        val hash = HashUtil.sha256("Delete test")
        val tx = Transaction(
            amountPaise = 3000L,
            transactionType = TransactionType.DEBIT,
            paymentMethod = PaymentMethod.ATM,
            accountType = AccountType.BANK_ACCOUNT,
            bank = "HDFC",
            merchantName = "ATM",
            payeeId = null,
            accountLastFour = "1234",
            refNumber = null,
            timestamp = 1690000000L,
            smsHash = hash,
            category = "ATM",
            parserConfidence = 0.9f
        )
        val id = repository.insertTransaction(tx)
        assertNotNull(repository.getTransactionById(id))

        val deletedRows = repository.deleteTransaction(id)
        assertEquals(1, deletedRows)
        assertNull(repository.getTransactionById(id))
    }

    @Test
    fun testRetrievalByPayee() {
        val payee = "target@upi"
        val tx1 = Transaction(
            amountPaise = 1000L, transactionType = TransactionType.DEBIT, paymentMethod = PaymentMethod.UPI,
            accountType = AccountType.BANK_ACCOUNT, bank = "HDFC", merchantName = "T1", payeeId = payee,
            accountLastFour = "1234", refNumber = "r1", timestamp = 1000L, smsHash = HashUtil.sha256("h1"),
            category = "SHOPPING", parserConfidence = 0.9f
        )
        val tx2 = Transaction(
            amountPaise = 2000L, transactionType = TransactionType.DEBIT, paymentMethod = PaymentMethod.UPI,
            accountType = AccountType.BANK_ACCOUNT, bank = "HDFC", merchantName = "T2", payeeId = payee,
            accountLastFour = "1234", refNumber = "r2", timestamp = 2000L, smsHash = HashUtil.sha256("h2"),
            category = "SHOPPING", parserConfidence = 0.9f
        )
        repository.insertTransaction(tx1)
        repository.insertTransaction(tx2)

        val results = repository.getTransactionsByPayee(payee)
        assertEquals(2, results.size)
        assertEquals(2000L, results[0].timestamp)
    }

    @Test
    fun testRetrievalByDateRange() {
        val tx1 = Transaction(
            amountPaise = 1000L, transactionType = TransactionType.DEBIT, paymentMethod = PaymentMethod.UPI,
            accountType = AccountType.BANK_ACCOUNT, bank = "HDFC", merchantName = "T1", payeeId = null,
            accountLastFour = "1234", refNumber = "r1", timestamp = 1500L, smsHash = HashUtil.sha256("h3"),
            category = "OTHER", parserConfidence = 0.9f
        )
        val tx2 = Transaction(
            amountPaise = 2000L, transactionType = TransactionType.DEBIT, paymentMethod = PaymentMethod.UPI,
            accountType = AccountType.BANK_ACCOUNT, bank = "HDFC", merchantName = "T2", payeeId = null,
            accountLastFour = "1234", refNumber = "r2", timestamp = 2500L, smsHash = HashUtil.sha256("h4"),
            category = "OTHER", parserConfidence = 0.9f
        )
        val tx3 = Transaction(
            amountPaise = 3000L, transactionType = TransactionType.DEBIT, paymentMethod = PaymentMethod.UPI,
            accountType = AccountType.BANK_ACCOUNT, bank = "HDFC", merchantName = "T3", payeeId = null,
            accountLastFour = "1234", refNumber = "r3", timestamp = 3500L, smsHash = HashUtil.sha256("h5"),
            category = "OTHER", parserConfidence = 0.9f
        )
        repository.insertTransaction(tx1)
        repository.insertTransaction(tx2)
        repository.insertTransaction(tx3)

        val range = repository.getTransactionsByDateRange(2000L, 3000L)
        assertEquals(1, range.size)
        assertEquals(2500L, range[0].timestamp)
    }

    @Test
    fun testPayeeCategoryMappingsNormalizationAndPersistence() {
        val rawPayee = "  RameshKumar@UPI  "
        assertNull(repository.getCategoryForPayee(rawPayee))

        repository.savePayeeCategoryMapping(rawPayee, "FOOD")

        assertEquals("FOOD", repository.getCategoryForPayee("rameshkumar@upi"))
        assertEquals("FOOD", repository.getCategoryForPayee("  RAMESHKUMAR@UPI "))

        repository.savePayeeCategoryMapping("rameshkumar@upi", "SHOPPING")
        assertEquals("SHOPPING", repository.getCategoryForPayee(rawPayee))
    }

    @Test
    fun testUpdateCategoriesForPayeeAndFutureTransaction() {
        val payee = "ramesh@upi"
        val tx1 = Transaction(
            amountPaise = 1000L, transactionType = TransactionType.DEBIT, paymentMethod = PaymentMethod.UPI,
            accountType = AccountType.BANK_ACCOUNT, bank = "HDFC", merchantName = "Ramesh", payeeId = payee,
            accountLastFour = "1234", refNumber = "r1", timestamp = 1000L, smsHash = HashUtil.sha256("h1"),
            category = "GROCERIES", parserConfidence = 0.9f
        )
        val tx2 = Transaction(
            amountPaise = 2000L, transactionType = TransactionType.DEBIT, paymentMethod = PaymentMethod.UPI,
            accountType = AccountType.BANK_ACCOUNT, bank = "HDFC", merchantName = "Ramesh", payeeId = payee,
            accountLastFour = "1234", refNumber = "r2", timestamp = 2000L, smsHash = HashUtil.sha256("h2"),
            category = "GROCERIES", parserConfidence = 0.9f
        )
        val tx3 = Transaction(
            amountPaise = 3000L, transactionType = TransactionType.DEBIT, paymentMethod = PaymentMethod.UPI,
            accountType = AccountType.BANK_ACCOUNT, bank = "HDFC", merchantName = "Other", payeeId = "other@upi",
            accountLastFour = "1234", refNumber = "r3", timestamp = 3000L, smsHash = HashUtil.sha256("h3"),
            category = "GROCERIES", parserConfidence = 0.9f
        )
        repository.insertTransaction(tx1)
        repository.insertTransaction(tx2)
        repository.insertTransaction(tx3)

        repository.savePayeeCategoryMapping(payee, "TRANSFER")
        repository.updateCategoriesForPayee(payee, "TRANSFER")

        val updatedTransactions = repository.getAllTransactions()
        val rameshTxs = updatedTransactions.filter { it.payeeId == payee }
        assertEquals(2, rameshTxs.size)
        assertEquals("TRANSFER", rameshTxs[0].category)
        assertEquals("TRANSFER", rameshTxs[1].category)

        val otherTx = updatedTransactions.first { it.payeeId == "other@upi" }
        assertEquals("GROCERIES", otherTx.category)

        val remembered = repository.getCategoryForPayee("  RAMESH@UPI ")
        assertEquals("TRANSFER", remembered)
    }

    @Test
    fun testNoPayeeIdentifierBehavior() {
        repository.savePayeeCategoryMapping("", "FOOD")
        assertNull(repository.getCategoryForPayee(""))
    }
}
