Outline of a presentation that explains my reasoning behind the program analysis generator and how it connects to
our market research.

1. The market: many developers want to spend less time reviewing code, especially AI-generated code. They want
   tools that check specific rules automatically. Our interviews surfaced several high-value rules:
   - Temporal workflows are deterministic (replaying a workflow must produce the same result, or production breaks).
   - Every API endpoint requires authentication.
   - No low-level data races (e.g. a resource is used before it is created).
   - Business logic is respected, e.g. an order never ships before its payment succeeds.
   - No long-running task blocks the user interface thread.
   - No hard-coded encryption keys anywhere in the program.
2. Why is it hard to build a business on these? Each rule is specific to one company, or one framework.
   - Classically, each rule needs its own purpose-built checker, written by program analysis experts.
   - e.g. deep dive: a 10-person team at Google built the infrastructure for hard-coded keys and a few similar
     Android properties.
   - e.g. Temporal has its own team building a lint tool for the call-graph-related rules.
   - So the market is a long tail of niches, and each niche costs an expert team to serve.
3. Why is this an interesting problem? Because in theory, all of these rules are the same problem.
   - Rice's theorem says every interesting question about what a program does is undecidable: no tool answers
     it perfectly for all programs.
   - The proof works by translating one question about program behavior into another.
   - We can use the same trick in practice: rewrite a program so that a company-specific rule becomes a generic
     one that existing tools already check.
   - Example: turn "an order never ships before payment" (from point 1) into "the program never crashes with a
     null pointer exception."
   ```java
   class Order {
     Receipt receipt = null;               // added for the check: null until paid
   }

   for (Order o : cart) {
     if (payments.charge(o)) {
       o.receipt = new Receipt();          // added: set only when payment succeeds
     }
   }
   ...
   void ship(Order o) {
     o.receipt.hashCode();                 // added: crashes exactly when o is unpaid
     ...
   }
   ```
   - The translation is exact: the program crashes in `ship` if and only if an unpaid order ships.
   - So in principle, any off-the-shelf null pointer checker, a mature and widely deployed kind of tool, can now
     check a business rule.
4. The catch: the abstraction problem.
   - In practice, every modern null pointer checker flags `ship` on every program, correct or not.
   - The reason: the checker sees a field that starts out null and is only sometimes set. It has no way to connect
     "non-null here" to "`charge` returned true for this order."
   - The translation lost nothing. The checker's abstraction, its built-in idea of what to keep track of, is wrong
     for this rule. A purpose-built checker would track each order's payment status, and nothing else.
   - That is why each rule has needed its own expert team: picking the abstraction is the expert work.
   - Our idea: a quality or security engineer writes the translation, which needs no analysis expertise, and our
     tool finds the right abstraction for it automatically. One product serves the whole long tail.
