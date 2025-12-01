import arrow.core.Either
import arrow.core.Option
import com.iainschmitt.januaryplaygroundbackend.shared.*
import ledger.Ledger
import ledger.LedgerK
import ledger.LedgerState
import ledger.LedgerTableEntry
import ledger.LedgerTableOperation
import ledger.LedgerTableOperationType
import ledger.positionLedgerOperation
import ledger.sessionLedgerOperation
import ledger.userLedgerOperation

class StreamingAuthDao(
    private val ledger: Ledger
) {
    fun createUser(
        email: String, passwordHash: String, accountType: AccountType = AccountType.STANDARD,
        orchestratedBy: String? = null
    ) =
        Either.catch {
            val user = ledger.submit(
                listOf(
                    userLedgerOperation(
                        LedgerTableOperationType.Create,
                        email,
                        passwordHash,
                        0,
                        accountType,
                        orchestratedBy
                    )
                )
            ) { state -> state.users[LedgerK.Users(email)] }

            user.get()
            Unit
        }

    fun getMaybePasswordHash(email: String) = Option.fromNullable(ledger.getLedgerState().users[LedgerK.Users(email)])


    fun createOrchestratedUser(dto: OrchestratedCredentialsDto, passwordHash: String, orchestratorEmail: String) =
        // The solution is sending a partial ledger record where an updating funciton is passed of the value type
        Either.catch {
            ledger.submitWithHandle({ }) { state ->

                //TODO: convert this to an Arrow raise instead of the exception catch, but this is complicated
                // somewhat by the use of the Either.catch

                val orchestrator = state.users[LedgerK.Users(orchestratorEmail)]
                    ?: throw RuntimeException("Orchestrated users error")

                listOf(
                    userLedgerOperation(
                        LedgerTableOperationType.Update,
                        orchestratorEmail,
                        orchestrator.passwordHash,
                        orchestrator.balance - dto.initialCreditBalance,
                        orchestrator.type,
                        orchestrator.orchestratedBy
                    ),
                    userLedgerOperation(
                        LedgerTableOperationType.Create,
                        dto.userEmail,
                        passwordHash,
                        dto.initialCreditBalance,
                        AccountType.STANDARD,
                        orchestratorEmail
                    )
                )
            }.get()
        }

    fun liquidateOrchestratedUser(dto: LiquidateOrchestratedUserDto, auth: Pair<String, Long>, targetUserBalance: Int) =
        Either.catch {
            ledger.submitWithHandle({ }) { state ->
                //TODO: convert this to an Arrow raise instead of the exception catch, but this is complicated
                // somewhat by the use of the Either.catch
                val orchestratedUser = state.users[LedgerK.Users(dto.targetUserEmail)]
                    ?: throw RuntimeException("Orchestrated users error")

                assert(orchestratedUser.orchestratedBy == auth.first)

                val orchestrator = state.users[LedgerK.Users(auth.first)]
                    ?: throw RuntimeException("Orchestrated users error")

                val referenceTime = System.currentTimeMillis()

                liquidateSingleUserOrchestratedPositions(
                    state, dto.orchestratorEmail, dto.targetUserEmail,
                    referenceTime
                ).toCollection(
                    mutableListOf(
                        userLedgerOperation(
                            LedgerTableOperationType.Update,
                            dto.targetUserEmail,
                            orchestratedUser.passwordHash,
                            orchestratedUser.balance - targetUserBalance,
                            orchestratedUser.type,
                            orchestratedUser.orchestratedBy
                        ),
                        userLedgerOperation(
                            LedgerTableOperationType.Create,
                            auth.first,
                            orchestrator.passwordHash,
                            orchestrator.balance + targetUserBalance,
                            orchestrator.type,
                            orchestrator.orchestratedBy
                        )
                    )
                )
            }.get()
        }

    private fun liquidateSingleUserOrchestratedPositions(
        state: LedgerState,
        orchestratorEmail: String,
        userEmail: String,
        referenceTime: Long,
    ): List<LedgerTableOperation> {
        val orchestratedUser =
            state.users[LedgerK.Users(userEmail)] ?: throw RuntimeException("Orchestrated users error")
        assert(orchestratedUser.orchestratedBy == orchestratorEmail)

        val orchestrator = state.users[LedgerK.Users(orchestratorEmail)]
        assert(orchestrator != null)

        // Short orders: would have to be mindful about consolidation
        val orchestratedUserLongPositions = state.positionRecords.filter {
            (it.key.userEmail == userEmail) && (it.key.positionType == PositionType.LONG)
        }

        val orchestratorLongPositions = state.positionRecords.filter {
            (it.key.userEmail == orchestratorEmail) && (it.key.positionType == PositionType.LONG)
        }

        return orchestratedUserLongPositions.flatMap { userPosition ->
            val deleteOperation =
                LedgerTableOperation(
                    LedgerTableEntry.PositionRecords(
                        userPosition.key,
                        userPosition.value
                    ),
                    LedgerTableOperationType.Delete
                )

            val orchestratorPosition = orchestratorLongPositions[LedgerK.PositionRecords(
                orchestratorEmail,
                userPosition.key
                    .ticker, userPosition.key.positionType
            )]

            return@flatMap if (orchestratorPosition != null) {
                listOf(
                    deleteOperation,
                    positionLedgerOperation(
                        LedgerTableOperationType.Create,
                        orchestratorEmail,
                        userPosition.key.ticker,
                        userPosition.key.positionType,
                        orchestratorPosition.size + userPosition.value.size,
                        referenceTime
                    ),
                )
            } else {
                listOf(
                    deleteOperation,
                    positionLedgerOperation(
                        LedgerTableOperationType.Create,
                        orchestratorEmail,
                        userPosition.key.ticker,
                        userPosition.key.positionType,
                        userPosition.value.size,
                        referenceTime
                    ),
                )
            }
        }
    }

    fun liquidateAllOrchestratedUsers(orchestratorEmail: String) =
        Either.catch {
            //TODO: remove the orchestrated users
            val referenceTime = System.currentTimeMillis()
            ledger.submitWithHandle({}) { state ->
                val orchestratedUsers = state.users.filter { it.value.orchestratedBy == orchestratorEmail }
                val orchestrator = state.users[LedgerK.Users(orchestratorEmail)]

                assert(orchestrator != null)
                val totalBalance = orchestratedUsers.map { it.value.balance }.sum()

                val ledgerTableOperations = orchestratedUsers.map {
                    userLedgerOperation(
                        LedgerTableOperationType.Delete,
                        it.key.email,
                        it.value.passwordHash,
                        it.value.balance,
                        it.value.type,
                        it.value.orchestratedBy
                    )
                }.toMutableList()


                ledgerTableOperations.add(
                    userLedgerOperation(
                        LedgerTableOperationType.Update,
                        orchestratorEmail,
                        orchestrator!!.passwordHash, //Why do I need to specify the `!!` after assert?
                        orchestrator.balance + totalBalance,
                        orchestrator.type,
                        orchestrator.orchestratedBy
                    )
                )

                ledgerTableOperations.addAll(
                    orchestratedUsers.map { it.key.email }.flatMap {
                        liquidateSingleUserOrchestratedPositions(
                            state,
                            orchestratorEmail, it,
                            referenceTime
                        )
                    }
                )
                ledgerTableOperations
            }.get()
        }

    fun getOrchestratedUsersWithBalance(orchestratorEmail: String) = Either.catch {
        ledger.getLedgerState().users.filter { it.value.orchestratedBy == orchestratorEmail }.map {
            it.key.email to it
                .value
                .balance
        }
    }

    fun transferCredits(dto: CreditTransferDto, auth: Pair<String, Long>) = Either.catch {
        ledger.submitWithHandle({}) { state ->
            val source = state.users[LedgerK.Users(auth.first)]
            val target = state.users[LedgerK.Users(dto.targetUserEmail)]
            assert(source != null)
            assert(target != null)

            listOf(
                userLedgerOperation(
                    LedgerTableOperationType.Update,
                    auth.first,
                    source!!.passwordHash,
                    source.balance - dto.creditAmount,
                    source.type,
                    source.orchestratedBy
                ),
                userLedgerOperation(
                    LedgerTableOperationType.Update,
                    dto.targetUserEmail,
                    target!!.passwordHash,
                    target.balance + dto.creditAmount,
                    target.type,
                    target.orchestratedBy
                )
            )
        }.get()
    }

    fun deleteToken(token: String) = Either.catch {
        ledger.submitWithHandle({}) { state ->
            val session = state.sessions[LedgerK.Sessions(token)]
            assert(session != null)
            listOf(
                sessionLedgerOperation(
                    LedgerTableOperationType.Delete, token, session!!.expireTimestamp, session
                        .email
                )
            )
        }.get()
    }

    fun getAuthFromToken(token: String) =
        ledger.getLedgerState().sessions.filter { it.key.token == token }.map {
            it.key.token to it.value
                .expireTimestamp
        }.firstOrNull()

    fun emailPresent(email: String) = ledger.getLedgerState().sessions.filter { it.value.email == email }.isNotEmpty()

    fun isOrchestrator(email: String) = ledger.getLedgerState().users.filter {
        it.key.email == email && it.value.type == AccountType.ORCHESTRATOR
    }
        .isNotEmpty()

    fun isOrchestratedBy(targetUserEmail: String, orchestratorEmail: String) = ledger.getLedgerState().users.filter {
        it.key.email == targetUserEmail && it.value.orchestratedBy == orchestratorEmail
    }
        .isNotEmpty()

    fun hasAtLeastAsManyCredits(email: String, credits: Int) = ledger.getLedgerState().users.filter {
        it.key.email ==
                email && it.value.balance >= credits
    }.isNotEmpty()

    fun getBalance(email: String) =
        Option.fromNullable(ledger.getLedgerState().users.filter { it.key.email == email }.map {
            it
                .value
                .balance
        }.firstOrNull())

    fun insertSession(token: String, expireTimestamp: Long, email: String) = ledger.submit(
        listOf(
            sessionLedgerOperation(
                LedgerTableOperationType.Create,
                token,
                expireTimestamp,
                email
            )
        )
    ) {}.get()

    fun removeExistingSessions(email: String) = ledger.submitWithHandle({}) { state ->
        state.sessions.filter { it.value.email == email }.map {
            sessionLedgerOperation(
                LedgerTableOperationType.Delete,
                it.key.token, it.value.expireTimestamp, it.value.email
            )
        }
    }.get()
}
