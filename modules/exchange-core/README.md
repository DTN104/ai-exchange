# Exchange core

Milestone B implements a Java 21, single-thread-confined matching and risk library for synthetic symbol AIX. No Spring, HTTP, database, clock or random generator is used by the production code.

## Supported rules

- LIMIT GTC and IOC; best price then arrival sequence (FIFO).
- Trades execute at the resting maker's price. Partial makers retain priority.
- Buys reserve limit-price notional; sells reserve shares. Execution, price improvement and cancellation release exactly the corresponding reserves.
- No shorting or margin. Initial endowments are the only source of money/shares; fees are zero.
- Self-trade prevention cancels the incoming remainder upon reaching an own contra order, keeping earlier third-party fills and never skipping the own maker.
- The final open-order limit defaults to 20,000. It can be lowered through the constructor. IOC/full fills do not require a new resting slot.

## Example

```java
import java.util.Map;
import io.aiexchange.core.Exchange;
import io.aiexchange.core.ExchangeCommand.*;

var exchange = new Exchange(Map.of(
    1L, new Exchange.Endowment(1_000_000, 1_000),
    2L, new Exchange.Endowment(1_000_000, 1_000)));

exchange.execute(new PlaceOrder(exchange.snapshot().nextOrderId(),
    1, "AIX", Side.SELL, TimeInForce.GTC, 100, 10));
var result = exchange.execute(new PlaceOrder(exchange.snapshot().nextOrderId(),
    2, "AIX", Side.BUY, TimeInForce.GTC, 105, 6));

// result.events() contains TradeExecuted(makerOrderId=1, takerOrderId=2, price=100, quantity=6).
// exchange.snapshot().asks().getFirst().quantity() == 4.
exchange.execute(new CancelOrder(1, "AIX", 1));
```

The example's behavior is exercised by ExchangeTest. From the repository root:

```sh
./mvnw -pl modules/exchange-core test
```

## Command boundaries

`plan(command)` returns an opaque immutable `Exchange.Plan` without changing committed state. `apply(plan)` accepts that plan only on its issuing engine at the original command sequence. Applying twice, applying after another command, or using another engine fails before mutation. `execute(command)` is the in-memory convenience for plan + apply.

Plans contain immutable, ordered domain events with contiguous global event and command sequence metadata. Business rejections are events too. Plans cannot be constructed by callers, so arbitrary unvalidated event lists cannot be injected through apply. An unexpected reducer exception fail-stops that engine rather than accepting more commands on uncertain state.

Keep all plan/apply/query calls on one owner thread. Immutable snapshots may be handed to readers after a batch commits; the Exchange object itself is not thread-safe.

## IDs and numeric limits

- Callers use `snapshot().nextOrderId()` for the next PlaceOrder. IDs start at 1; attempting the current ID consumes it even when the order is rejected for a business reason. Reusing an old ID or skipping ahead rejects without advancing the counter. Cancel commands do not consume an order ID. Exhaustion stops new ID allocation.
- IDs, prices, quantities, balances and counters are longs; prices and quantities must be positive. Multiplication, addition, subtraction and sequence increments use checked arithmetic.
- Genesis requires 1–500 positive trader IDs with nonnegative endowments. Aggregate cash and aggregate shares must each fit a long. This bounds all later per-account balances while allowing checked near-Long.MAX_VALUE settlement.
- A tick is one synthetic money unit and a lot is one synthetic share. These are not HOSE tick/lot rules.

## Verification and remaining scope

Tests cover priority, maker pricing, partial fills, IOC, cancel, ownership, funds/stock validation, overflow, self-trade, capacity, pure planning and immutable views. A fixed-seed test runs 5,000 mixed commands across 8 accounts, recomputing conservation and reservation invariants using independent BigInteger arithmetic after every command. Reversed genesis insertion order must produce identical event lists and snapshots.

This establishes in-memory command determinism for that workload, not persisted replay or realism. Journal durability, decoding/replay of saved events, crash recovery, trader scheduling, external order APIs and a running web market remain later milestones. The server still serves status only.
