Outline of a presenation that explains my reasoning behind the program analysis generator and how it is connected with
our market research.

1. The market: a wide variety of developers who really want a way to reduce the burden of reviewing code (especially 
Ai generated code). Several high value targets for program analysis came up in our interveiws:
   - Temporal determinism checks 
   - Check that every end point requires authentication 
   - Prevent low-level data races (e.g. a resource is used before created)
   - Find problems in business logic such as an order going out before payment has successfully processed.
   - Long-running tasks on the user interface thread
   - Determine if hard-coded encryption keys are used anywhere in a program.
2. Why is it challenging to build a business around these? Each domain is very specific to a company 
   - Classically, each one of these tasks would require a purpose built safety checker.
   - e.g. deep dive was a 10 person team at google that built the infrastructure for hard-coded keys and a few similar android properties
   - Temporal has a team building a lint tool to catch the more call-graph related properties
3. Why is this an interesting problem?
   - Rice's theorem classically states that any interesting problem is undecidable 
   - This proof works by showing any "interesting" property can be compiled into any other interesting property.
   - What happens if we turn one of these domains into another one?
   - Example: reduce order-before-payment (from point 1) to a null pointer exception.
   ```java
   class Order {
     Receipt receipt = null;               // ghost: null until paid
   }

   for (Order o : cart) {
     if (payments.charge(o)) {
       o.receipt = new Receipt();          // ghost: non-null once paid
     }
   }
   ...
   void ship(Order o) {
     o.receipt.hashCode();                 // inserted: throws NPE iff o is unpaid
     ...
   }
   ```
   - The reduction is exact: `ship` throws a null pointer exception if and only if an unpaid order ships.
   - So in principle a decades-old, industrial-strength null checker can now check a business rule.
4. The abstraction problem.
   - In practice this reduction flags `ship` on every program with any modern null pointer checker.  Correct or not: it sees a field that starts null and is only
     sometimes set, and cannot tie "non-null here" to "`charge` returned true for this order."
   - What if we could make the program analysis smarter so that a quality/security engineer can do the reduction and our tool chooses the right technique?
