package io.github.yufeiyufei888.hearthcrew.backend.numen;

/** Added to the frozen upstream context at construction on the server thread. */
public interface OwnedSearchContext {
    SearchBudget hearthcrew$searchBudget();
}
