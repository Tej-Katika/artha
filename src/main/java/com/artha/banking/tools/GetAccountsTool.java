package com.artha.banking.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.artha.core.agent.ArthaTool;
import com.artha.core.agent.FinancialTool;
import com.artha.core.agent.ToolContext;
import com.artha.core.agent.ToolResult;
import com.artha.banking.ontology.BankAccount;
import com.artha.banking.ontology.BankAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Account-enumeration tool. Lists every bank account on file for the user
 * (type, institution, masked number, balance) so the agent can answer
 * "which accounts do I have?" and, crucially, can ASSERT NON-EXISTENCE:
 * if no account of a requested type (savings, credit card, brokerage) is
 * present, the agent has positive evidence to say "you have no such
 * account" rather than hedging ("I don't see that in the data available
 * to me"). This closes the account-existence residual in the grounding
 * study, where the agent could refuse to fabricate but could not firmly
 * confirm absence for lack of an enumeration affordance.
 */
@Slf4j
@ArthaTool(
    description = "List all of the user's bank accounts (type, institution, balance); use to confirm which accounts exist or that a requested account does not",
    category    = "data",
    version     = "1.0.0"
)
@Component
@RequiredArgsConstructor
public class GetAccountsTool implements FinancialTool {

    private final BankAccountRepository bankAccountRepository;

    @Override
    public String getName() { return "get_accounts"; }

    @Override
    public Object getDefinition() {
        return Map.of(
            "name", getName(),
            "description", """
                List every bank account on file for the user, with account type
                (checking, savings, credit_card, etc.), institution name, masked
                account number, and current balance.
                Use this whenever the user refers to a specific account or account
                type to FIRST confirm that account actually exists. The returned
                list is the complete and authoritative set of the user's accounts:
                if a requested type (e.g. savings, credit card, brokerage, or a
                second checking account) is NOT in the list, the user does not have
                one, and you should say so plainly rather than estimate or fabricate
                its balance, APR, or other details.
                """,
            "input_schema", Map.of(
                "type",       "object",
                "properties", Map.of(),
                "required",   List.of()
            )
        );
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext context) {
        try {
            long start    = System.currentTimeMillis();
            UUID userUUID = UUID.fromString(context.userId());

            List<BankAccount> accounts =
                bankAccountRepository.findByUserIdAndIsActiveTrue(userUUID);

            // Distinct normalized account types present, to make absence
            // checks trivial for the agent.
            List<String> typesPresent = accounts.stream()
                .map(BankAccount::getAccountType)
                .filter(Objects::nonNull)
                .map(t -> t.toLowerCase(Locale.ROOT))
                .distinct()
                .sorted()
                .collect(Collectors.toList());

            if (accounts.isEmpty()) {
                return ToolResult.okWithTiming(Map.of(
                    "account_count",  0,
                    "accounts",       List.of(),
                    "types_present",  List.of(),
                    "message",
                        "No bank accounts are on file for this user. Do not refer "
                        + "to any specific account, balance, or account type as if "
                        + "it exists."
                ), start);
            }

            List<Map<String, Object>> accountList = accounts.stream()
                .map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("account_type",    a.getAccountType());
                    m.put("account_name",    a.getAccountName());
                    m.put("institution",     a.getInstitutionName());
                    m.put("mask",            a.getMask());
                    m.put("current_balance", a.getCurrentBalance());
                    m.put("currency",        a.getCurrencyCode());
                    return m;
                })
                .collect(Collectors.toList());

            return ToolResult.okWithTiming(Map.of(
                "account_count", accountList.size(),
                "accounts",      accountList,
                "types_present", typesPresent,
                "note",
                    "This is the complete list of the user's accounts. Any account "
                    + "type not in 'types_present' does not exist for this user."
            ), start);

        } catch (Exception e) {
            log.error("GetAccountsTool error: {}", e.getMessage());
            return ToolResult.error("Failed to list accounts: " + e.getMessage());
        }
    }
}
