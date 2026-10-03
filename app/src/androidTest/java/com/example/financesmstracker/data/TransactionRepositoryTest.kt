package com.example.financesmstracker.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.financesmstracker.parser.AccountType
import com.example.financesmstracker.parser.PaymentMethod
import com.example.financesmstracker.parser.TransactionType
import com.example.financesmstracker.util.HashUtil
import com.example.financesmstracker.integration.OracleTransaction
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
    @Test
    fun oracleGmailCorrectsBanklessWrongDirectionNotification() {
        val provisional = repository.insertTransaction(
            Transaction(
                amountPaise = 200L,
                transactionType = TransactionType.DEBIT,
                paymentMethod = PaymentMethod.UNKNOWN,
                accountType = AccountType.BANK_ACCOUNT,
                bank = null,
                merchantName = null,
                payeeId = null,
                accountLastFour = "3370",
                refNumber = null,
                timestamp = 1_800_000_000_000L,
                smsHash = "notification:bankless-axis",
                category = "GROCERIES",
                parserConfidence = 0.70f
            )
        )

        val remote = OracleTransaction(
            id = "gmail:bankless-axis",
            amountMinor = 200L,
            currency = "INR",
            transactionType = "CREDIT",
            paymentMethod = "UPI",
            accountType = "BANK_ACCOUNT",
            bank = "AXIS",
            merchantOrPayee = "ABDUL WAS",
            accountLast4 = "3370",
            reference = "898523485227",
            timestamp = 1_800_000_060_000L,
            category = "TRANSFER",
            confidence = 1.0f
        )

        repository.upsertOracleGmailTransaction(remote)

        val corrected = repository.getTransactionById(provisional)
        assertEquals(TransactionType.CREDIT, corrected?.transactionType)
        assertEquals(PaymentMethod.UPI, corrected?.paymentMethod)
        assertEquals("AXIS", corrected?.bank)
        assertEquals("3370", corrected?.accountLastFour)
        assertEquals("898523485227", corrected?.refNumber)
    }

    @Test
    fun smsEnrichesExistingGmailNotificationInsteadOfCreatingDuplicate() {
        val notificationId = repository.insertTransaction(
            Transaction(
                amountPaise = 600L,
                transactionType = TransactionType.CREDIT,
                paymentMethod = PaymentMethod.UPI,
                accountType = AccountType.BANK_ACCOUNT,
                bank = "HDFC",
                merchantName = "HDFC",
                payeeId = null,
                accountLastFour = "9591",
                refNumber = null,
                timestamp = 1_800_000_000_000L,
                smsHash = "notification:hdfc-6",
                category = "TRANSFER",
                parserConfidence = 0.90f
            )
        )

        val sms = Transaction(
            amountPaise = 600L,
            transactionType = TransactionType.CREDIT,
            paymentMethod = PaymentMethod.UPI,
            accountType = AccountType.BANK_ACCOUNT,
            bank = "HDFC",
            merchantName = "HDFC Bank",
            payeeId = null,
            accountLastFour = "9591",
            refNumber = "240201254528",
            timestamp = 1_800_000_060_000L,
            smsHash = HashUtil.sha256("hdfc-sms-6"),
            category = "TRANSFER",
            parserConfidence = 0.99f
        )

        val returnedId = repository.insertTransaction(sms)

        assertEquals(notificationId, returnedId)
        val active = repository.getAllTransactions().filter {
            it.amountPaise == 600L && it.bank == "HDFC"
        }
        assertEquals(1, active.size)
        assertEquals("240201254528", active.single().refNumber)
        assertEquals(HashUtil.sha256("hdfc-sms-6"), active.single().smsHash)
    }

    @Test
    fun smsMergesBanklessWrongDirectionNotificationAndHdfcBankAlias() {
        val timestamp = 1_800_000_000_000L

        val provisional = repository.insertTransaction(
            Transaction(
                amountPaise = 500L,
                transactionType = TransactionType.DEBIT,
                paymentMethod = PaymentMethod.UNKNOWN,
                accountType = AccountType.BANK_ACCOUNT,
                bank = null,
                merchantName = null,
                payeeId = null,
                accountLastFour = null,
                refNumber = null,
                timestamp = timestamp,
                smsHash = "notification:bankless-wrong-direction-5",
                category = "GROCERIES",
                parserConfidence = 0.70f
            )
        )
        assertTrue(provisional > 0)

        val sms = Transaction(
            amountPaise = 500L,
            transactionType = TransactionType.CREDIT,
            paymentMethod = PaymentMethod.UPI,
            accountType = AccountType.BANK_ACCOUNT,
            bank = "HDFC Bank",
            merchantName = "HDFC Bank",
            payeeId = null,
            accountLastFour = "9591",
            refNumber = "502395202128",
            timestamp = timestamp + 30_000L,
            smsHash = HashUtil.sha256("hdfc-sms-5-bank-alias"),
            category = "TRANSFER",
            parserConfidence = 0.99f
        )

        val returnedId = repository.insertTransaction(sms)

        assertEquals(provisional, returnedId)
        val active = repository.getAllTransactions().filter { it.amountPaise == 500L }
        assertEquals(1, active.size)
        assertEquals(TransactionType.CREDIT, active.single().transactionType)
        assertEquals("HDFC Bank", active.single().bank)
        assertEquals("9591", active.single().accountLastFour)
        assertEquals("502395202128", active.single().refNumber)
        assertEquals(HashUtil.sha256("hdfc-sms-5-bank-alias"), active.single().smsHash)
    }

    @Test
    fun oracleGmailReferenceCorrectsAxisNotificationAndVoidsWrongAxisDebit() {
        val rrn = "739593577194"
        val timestamp = 1_800_000_000_000L

        val hdfcDebit = repository.insertTransaction(
            Transaction(
                amountPaise = 400L,
                transactionType = TransactionType.DEBIT,
                paymentMethod = PaymentMethod.UPI,
                accountType = AccountType.BANK_ACCOUNT,
                bank = "HDFC",
                merchantName = "ABDUL WASIQ",
                payeeId = null,
                accountLastFour = "9591",
                refNumber = rrn,
                timestamp = timestamp,
                smsHash = HashUtil.sha256("hdfc-$rrn"),
                category = "OTHER",
                parserConfidence = 0.99f
            )
        )

        val axisNotification = repository.insertTransaction(
            Transaction(
                amountPaise = 400L,
                transactionType = TransactionType.CREDIT,
                paymentMethod = PaymentMethod.UNKNOWN,
                accountType = AccountType.BANK_ACCOUNT,
                bank = "AXIS",
                merchantName = null,
                payeeId = null,
                accountLastFour = null,
                refNumber = null,
                timestamp = timestamp,
                smsHash = "notification:axis-$rrn",
                category = "OTHER",
                parserConfidence = 0.90f
            )
        )

        val wrongAxisDebit = repository.insertTransaction(
            Transaction(
                amountPaise = 400L,
                transactionType = TransactionType.DEBIT,
                paymentMethod = PaymentMethod.UPI,
                accountType = AccountType.BANK_ACCOUNT,
                bank = "AXIS",
                merchantName = "ABDUL WAS",
                payeeId = null,
                accountLastFour = "3370",
                refNumber = "UPI/P2A/$rrn/ABDUL WAS/HDFC/Paym",
                timestamp = timestamp,
                smsHash = HashUtil.sha256("wrong-axis-$rrn"),
                category = "OTHER",
                parserConfidence = 0.99f
            )
        )

        val remote = OracleTransaction(
            id = "gmail:axis-$rrn",
            amountMinor = 400L,
            currency = "INR",
            transactionType = "CREDIT",
            paymentMethod = "UPI",
            accountType = "BANK_ACCOUNT",
            bank = "AXIS",
            merchantOrPayee = "ABDUL WAS",
            accountLast4 = "3370",
            reference = rrn,
            timestamp = timestamp + 60_000L,
            category = "OTHER",
            confidence = 1.0f
        )

        repository.upsertOracleGmailTransaction(remote)

        val hdfc = repository.getTransactionById(hdfcDebit)
        val axisCredit = repository.getTransactionById(axisNotification)
        val allActive = repository.getAllTransactions()

        assertEquals(TransactionType.DEBIT, hdfc?.transactionType)
        assertEquals("HDFC", hdfc?.bank)
        assertEquals(TransactionType.CREDIT, axisCredit?.transactionType)
        assertEquals("AXIS", axisCredit?.bank)
        assertEquals("3370", axisCredit?.accountLastFour)
        assertEquals(rrn, axisCredit?.refNumber)
        assertTrue(allActive.none { it.id == wrongAxisDebit })
    }


    @Test
    fun oracleGmailReferenceDoesNotLeaveDuplicateHdfcCreditFromWrongDirectionNotification() {
        val timestamp = 1_800_000_000_000L
        val rrn = "502395202128"

        // The bank SMS is the authoritative HDFC-side transaction.
        val hdfcCredit = repository.insertTransaction(
            Transaction(
                amountPaise = 500L,
                transactionType = TransactionType.CREDIT,
                paymentMethod = PaymentMethod.UPI,
                accountType = AccountType.BANK_ACCOUNT,
                bank = "HDFC",
                merchantName = "HDFC Bank",
                payeeId = null,
                accountLastFour = "9591",
                refNumber = null,
                timestamp = timestamp,
                smsHash = HashUtil.sha256("hdfc-sms-5"),
                category = "TRANSFER",
                parserConfidence = 0.99f
            )
        )

        // Gmail's fast notification path previously created a second provisional
        // row with the same amount/account but the wrong direction and no bank.
        val provisional = repository.insertTransaction(
            Transaction(
                amountPaise = 500L,
                transactionType = TransactionType.DEBIT,
                paymentMethod = PaymentMethod.UNKNOWN,
                accountType = AccountType.BANK_ACCOUNT,
                bank = null,
                merchantName = null,
                payeeId = null,
                accountLastFour = "9591",
                refNumber = null,
                timestamp = timestamp + 30_000L,
                smsHash = "notification:wrong-direction-5",
                category = "GROCERIES",
                parserConfidence = 0.70f
            )
        )

        val remote = OracleTransaction(
            id = "gmail:hdfc-5",
            amountMinor = 500L,
            currency = "INR",
            transactionType = "CREDIT",
            paymentMethod = "UPI",
            accountType = "BANK_ACCOUNT",
            bank = "HDFC",
            merchantOrPayee = "HDFC Bank",
            accountLast4 = "9591",
            reference = rrn,
            timestamp = timestamp + 60_000L,
            category = "TRANSFER",
            confidence = 1.0f
        )

        repository.upsertOracleGmailTransaction(remote)

        val allActive = repository.getAllTransactions()
        val hdfcCredits = allActive.filter {
            it.amountPaise == 500L &&
                it.bank == "HDFC" &&
                it.transactionType == TransactionType.CREDIT
        }

        assertEquals(1, hdfcCredits.size)
        assertEquals(hdfcCredit, hdfcCredits.single().id)
        assertEquals(rrn, hdfcCredits.single().refNumber)
        assertEquals(TransactionType.DEBIT, repository.getTransactionById(provisional)?.transactionType)
        assertTrue(allActive.none { it.id == provisional })
    }


    @Test
    fun banklessNotificationInfersUniqueBankFromAccountLastFour() {
        repository.insertTransaction(
            Transaction(
                amountPaise = 100L,
                transactionType = TransactionType.CREDIT,
                paymentMethod = PaymentMethod.UPI,
                accountType = AccountType.BANK_ACCOUNT,
                bank = "AXIS",
                merchantName = "Existing Axis",
                payeeId = null,
                accountLastFour = "3370",
                refNumber = "existing-axis",
                timestamp = 1_800_000_000_000L,
                smsHash = HashUtil.sha256("existing-axis"),
                category = "TRANSFER",
                parserConfidence = 0.99f
            )
        )

        val notificationId = repository.insertTransaction(
            Transaction(
                amountPaise = 500L,
                transactionType = TransactionType.DEBIT,
                paymentMethod = PaymentMethod.UNKNOWN,
                accountType = AccountType.BANK_ACCOUNT,
                bank = null,
                merchantName = null,
                payeeId = null,
                accountLastFour = "3370",
                refNumber = null,
                timestamp = 1_800_000_060_000L,
                smsHash = "notification:axis-5",
                category = "GROCERIES",
                parserConfidence = 0.70f
            )
        )

        assertEquals("AXIS", repository.getTransactionById(notificationId)?.bank)
    }


    @Test
    fun smsMergesIntoExistingOracleGmailRowInsteadOfCreatingDuplicate() {
        val remoteId = repository.upsertOracleGmailTransaction(
            OracleTransaction(
                id = "gmail:hdfc-existing",
                amountMinor = 500L,
                currency = "INR",
                transactionType = "DEBIT",
                paymentMethod = "UPI",
                accountType = "BANK_ACCOUNT",
                bank = "HDFC",
                merchantOrPayee = "ABDUL WASIQ",
                accountLast4 = "9591",
                reference = "502395202128",
                timestamp = 1_800_000_000_000L,
                category = "TRANSFER",
                confidence = 1.0f
            )
        )

        val smsId = repository.insertTransaction(
            Transaction(
                amountPaise = 500L,
                transactionType = TransactionType.DEBIT,
                paymentMethod = PaymentMethod.UPI,
                accountType = AccountType.BANK_ACCOUNT,
                bank = "HDFC",
                merchantName = "ABDUL WASIQ",
                payeeId = null,
                accountLastFour = "9591",
                refNumber = "502395202128",
                timestamp = 1_800_000_060_000L,
                smsHash = HashUtil.sha256("hdfc-sms-after-gmail"),
                category = "TRANSFER",
                parserConfidence = 0.99f
            )
        )

        assertEquals(remoteId, smsId)
        val active = repository.getAllTransactions().filter {
            it.amountPaise == 500L &&
                it.bank == "HDFC" &&
                it.transactionType == TransactionType.DEBIT
        }
        assertEquals(1, active.size)
        assertEquals(HashUtil.sha256("hdfc-sms-after-gmail"), active.single().smsHash)
        assertEquals("502395202128", active.single().refNumber)
    }

}
