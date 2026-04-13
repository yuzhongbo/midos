package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.civilization;

import java.util.List;

public record CivilizationAssignment(TaskRequest request,
                                     Offer selectedOffer,
                                     List<Offer> offers,
                                     Transaction transaction) {

    public CivilizationAssignment {
        offers = offers == null ? List.of() : List.copyOf(offers);
    }

    public static CivilizationAssignment empty(TaskRequest request, List<Offer> offers, Transaction transaction) {
        return new CivilizationAssignment(request, null, offers, transaction);
    }

    public boolean assigned() {
        return selectedOffer != null && transaction != null && transaction.settled();
    }

    public String selectedOrgId() {
        return selectedOffer == null ? "" : selectedOffer.orgId();
    }
}
