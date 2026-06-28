package com.beingadish.AroundU.chat.controller;

import com.beingadish.AroundU.chat.dto.ChatMessageRequest;
import com.beingadish.AroundU.chat.dto.ChatMessageResponseDTO;
import com.beingadish.AroundU.chat.dto.ConversationResponseDTO;
import com.beingadish.AroundU.chat.dto.JobConversationsDTO;
import com.beingadish.AroundU.chat.service.ChatService;
import com.beingadish.AroundU.common.dto.ApiResponse;
import com.beingadish.AroundU.infrastructure.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.beingadish.AroundU.common.constants.URIConstants.CHAT_BASE;

@RestController
@RequestMapping(CHAT_BASE)
@RequiredArgsConstructor
@Tag(name = "Chat", description = "Job-scoped messaging between client and worker")
@SecurityRequirement(name = "bearerAuth")
public class ChatController {

    private final ChatService chatService;

    private Long principalId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal up) {
            return up.getId();
        }

        throw new IllegalStateException("User not authenticated");
    }

    private String principalRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getAuthorities() != null) {
            return auth.getAuthorities().stream()
                    .map(a -> a.getAuthority().replace("ROLE_", ""))
                    .findFirst()
                    .orElse("UNKNOWN");
        }
        throw new IllegalStateException("User not authenticated");
    }

    @PostMapping("/jobs/{jobId}/messages")
    @Operation(summary = "Send message", description = "Send a chat message within a job conversation")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Message sent",
                content = @Content(schema = @Schema(implementation = ChatMessageResponseDTO.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Job not found")
    })
    public ResponseEntity<ApiResponse<ChatMessageResponseDTO>> sendMessage(
            @Parameter(description = "Job ID", required = true) @PathVariable Long jobId,
            @Valid @RequestBody ChatMessageRequest request) {
        ChatMessageResponseDTO dto = chatService.sendMessage(jobId, principalId(), principalRole(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(dto));
    }

    @GetMapping("/conversations/{conversationId}/messages")
    @Operation(summary = "Get conversation messages", description = "Retrieve paginated messages for a conversation")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Messages retrieved"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not a participant"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Conversation not found")
    })
    public ResponseEntity<ApiResponse<List<ChatMessageResponseDTO>>> getMessages(
            @Parameter(description = "Conversation ID", required = true) @PathVariable Long conversationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        List<ChatMessageResponseDTO> messages = chatService.getMessages(conversationId, principalId(), page, size);
        return ResponseEntity.ok(ApiResponse.success(messages));
    }

    @GetMapping("/conversations")
    @Operation(summary = "List conversations", description = "Get all conversations for the authenticated user (flat list)")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Conversations listed")
    })
    public ResponseEntity<ApiResponse<List<ConversationResponseDTO>>> getConversations() {
        List<ConversationResponseDTO> conversations = chatService.getConversations(principalId(), principalRole());
        return ResponseEntity.ok(ApiResponse.success(conversations));
    }

    @GetMapping("/conversations/grouped")
    @Operation(summary = "List conversations grouped by job",
            description = "Get conversations grouped by job — ideal for client/provider view")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Grouped conversations listed")
    })
    public ResponseEntity<ApiResponse<List<JobConversationsDTO>>> getConversationsGroupedByJob() {
        List<JobConversationsDTO> grouped = chatService.getConversationsGroupedByJob(principalId(), principalRole());
        return ResponseEntity.ok(ApiResponse.success(grouped));
    }

    @PostMapping("/conversations/{conversationId}/delivered")
    @Operation(summary = "Mark as delivered", description = "Mark all messages in a conversation as delivered")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Messages marked as delivered"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Conversation not found")
    })
    public ResponseEntity<ApiResponse<List<Long>>> markAsDelivered(
            @Parameter(description = "Conversation ID", required = true) @PathVariable Long conversationId) {
        List<Long> updatedIds = chatService.markAsDelivered(conversationId, principalId(), principalRole());
        return ResponseEntity.ok(ApiResponse.success(updatedIds));
    }

    @PostMapping("/conversations/{conversationId}/read")
    @Operation(summary = "Mark as read", description = "Mark all messages in a conversation as read for the user")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Messages marked as read"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Conversation not found")
    })
    public ResponseEntity<ApiResponse<List<Long>>> markAsRead(
            @Parameter(description = "Conversation ID", required = true) @PathVariable Long conversationId) {
        List<Long> updatedIds = chatService.markAsRead(conversationId, principalId(), principalRole());
        return ResponseEntity.ok(ApiResponse.success(updatedIds));
    }
}
