package com.bank.customer.infrastructure.web;

import com.bank.customer.domain.IdentityUserId;
import com.bank.customer.domain.port.in.IdentityLink;
import com.bank.customer.domain.port.in.LinkIdentityCommand;
import com.bank.customer.domain.port.in.LinkIdentityUseCase;
import com.bank.shared.kernel.domain.CustomerId;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityLinkControllerTest {

    private static final String USER = "6f1c2a7e-5b8d-4c3e-9a1f-0d2b3c4e5f60";

    @Test
    void linksThroughTheUseCaseAndAnswersWithTheLink() {
        LinkIdentityUseCase useCase = Mockito.mock(LinkIdentityUseCase.class);
        LinkIdentityCommand command = new LinkIdentityCommand(CustomerId.of("CUST-1A2B3C4D"), new IdentityUserId(USER));
        Mockito.when(useCase.linkIdentity(command)).thenReturn(new IdentityLink(command.customerId(), command.identityUserId()));

        var response = new IdentityLinkController(useCase)
            .linkIdentity("CUST-1A2B3C4D", new IdentityLinkController.IdentityLinkRequest(USER));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(new IdentityLinkController.IdentityLinkResponse("CUST-1A2B3C4D", USER));
    }

    @Test
    void anUnsafeUserIdIsRejectedBeforeTheUseCase() {
        LinkIdentityUseCase useCase = Mockito.mock(LinkIdentityUseCase.class);

        assertThatThrownBy(() -> new IdentityLinkController(useCase)
                .linkIdentity("CUST-1A2B3C4D", new IdentityLinkController.IdentityLinkRequest("../admin")))
            .isInstanceOf(IllegalArgumentException.class);
        Mockito.verifyNoInteractions(useCase);
    }
}
