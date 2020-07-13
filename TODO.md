Just a couple of ideas I dont want to forget.

- Instrument datafetchers based on the filters in the query instead of modifying the result afterwards. That seems cleaner. I just don't know how to yet :-)
- Add aggregations by adding a ..._meta field next to each list.
- Add filtering on offset/count as a predicate. Maybe have a synthetic "rownumber" field so you can have a predicate {rownumber: {gte: 10, lt:20}}? Of course it would be special because it has to be evaluated last to make sense.
  - Alternatively there could be a separate clause next to _filter.
  - Yet another way would be to make filtering compatible with subscriptions somehow.