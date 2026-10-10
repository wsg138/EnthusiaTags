package org.enthusia.tags;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CollectionLayoutTest {
 @Test void collectionGridReservesProductionControlsAndPagesEveryEntry() {
  assertEquals(21,CollectionMenuLayout.SLOTS.size());
  assertEquals(19,CollectionMenuLayout.SLOTS.get(0));
  assertEquals(43,CollectionMenuLayout.SLOTS.get(20));
  assertEquals(2,CollectionMenuLayout.pages(22));
  assertEquals(1,CollectionMenuLayout.page(99,22));
  assertEquals(0,CollectionMenuLayout.page(-1,0));
  assertFalse(CollectionMenuLayout.SLOTS.contains(49));
 }
}
